package dev.itayp.tasker.planning

import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Picks a bounded slate of TODO tasks for the LLM weekly planner. Returns two named
 * pools so the prompt template can label them: "urgent" (priority + deadline +
 * reschedule history) and "stale" (forgotten, not deferred). The LLM reasons over
 * both; we never send the full backlog.
 *
 * `today` and `weekStart` come from the caller (which knows the user's timezone) so
 * the relevantFrom filter and the "already planned for a later week" lookup operate
 * on local-day boundaries instead of UTC.
 */
@Service
class PlannerTaskSelector(
    private val backlogTaskRepository: BacklogTaskRepository,
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun select(
        userId: UUID,
        today: LocalDate,
        weekStart: LocalDate,
        zone: ZoneId,
        urgentSlots: Int = DEFAULT_URGENT_SLOTS,
        staleSlots: Int = DEFAULT_STALE_SLOTS,
    ): PlannerTaskSelection {
        val tasks = backlogTaskRepository
            .findAllByUserIdAndStatus(userId, TaskStatus.TODO)
            .map { it.toDomain() }
            .filter { it.relevantFrom == null || !it.relevantFrom.isAfter(today) }

        val totalSlots = urgentSlots + staleSlots
        val (urgent, stale) = if (tasks.size <= totalSlots) {
            tasks.sortedByDescending { urgencyScore(it, today) } to emptyList()
        } else {
            val urgentList = tasks
                .sortedByDescending { urgencyScore(it, today) }
                .take(urgentSlots)
            val urgentIds = urgentList.mapTo(mutableSetOf()) { it.id }

            val freshnessCutoff = clock.instant().minus(STALE_FRESHNESS_DAYS, ChronoUnit.DAYS)
            val staleList = tasks
                .asSequence()
                .filter { it.id !in urgentIds }
                .filter { it.rescheduleCount == 0 }
                .filter { (it.updatedAt ?: it.createdAt).isBefore(freshnessCutoff) }
                .sortedBy { it.updatedAt ?: it.createdAt }
                .take(staleSlots)
                .toList()
            urgentList to staleList
        }

        val selectedIds = (urgent + stale).map { it.id }
        val alreadyPlanned = lookupFuturePlannedDates(userId, selectedIds, weekStart, zone)

        return PlannerTaskSelection(urgent = urgent, stale = stale, alreadyPlanned = alreadyPlanned)
    }

    /**
     * For each candidate task, find the earliest planned slot whose start is on a day
     * strictly after the current planning window's end. Used to flag tasks the assistant
     * shouldn't re-schedule on top of an existing future plan.
     */
    private fun lookupFuturePlannedDates(
        userId: UUID,
        taskIds: List<UUID>,
        weekStart: LocalDate,
        zone: ZoneId,
    ): Map<UUID, LocalDate> {
        if (taskIds.isEmpty()) return emptyMap()
        val plannedTasks = plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(userId, taskIds)
        if (plannedTasks.isEmpty()) return emptyMap()

        val plannedTaskById = plannedTasks.associateBy { requireNotNull(it.id) }
        val slots = plannedTaskSlotRepository.findAllByPlannedTaskIdIn(plannedTaskById.keys)
        if (slots.isEmpty()) return emptyMap()

        // Strictly-after-window means: slot's local date in the user's zone is after the last day of the planning week.
        val windowLastDay = weekStart.plusDays(6)
        val result = mutableMapOf<UUID, LocalDate>()
        for (slot in slots) {
            val backlogTaskId = plannedTaskById[slot.plannedTaskId]?.backlogTaskId ?: continue
            val startIso = slot.startIso ?: continue
            val slotDate = runCatching { OffsetDateTime.parse(startIso).atZoneSameInstant(zone).toLocalDate() }
                .getOrNull() ?: continue
            if (!slotDate.isAfter(windowLastDay)) continue
            val existing = result[backlogTaskId]
            if (existing == null || slotDate.isBefore(existing)) {
                result[backlogTaskId] = slotDate
            }
        }
        return result
    }

    companion object {
        const val DEFAULT_URGENT_SLOTS = 12
        const val DEFAULT_STALE_SLOTS = 3
        private const val STALE_FRESHNESS_DAYS = 7L
    }
}

data class PlannerTaskSelection(
    val urgent: List<BacklogTask>,
    val stale: List<BacklogTask>,
    val alreadyPlanned: Map<UUID, LocalDate> = emptyMap(),
)

internal fun urgencyScore(task: BacklogTask, today: LocalDate): Double =
    priorityWeight(task.priority) +
        deadlineUrgency(task.deadline, today) +
        rescheduleNudge(task.rescheduleCount) +
        ageNudge(task.createdAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(), today)

internal fun priorityWeight(priority: TaskPriority?): Double = when (priority) {
    TaskPriority.HIGH -> 3.0
    TaskPriority.MEDIUM -> 2.0
    TaskPriority.LOW, null -> 1.0
}

internal fun deadlineUrgency(deadline: LocalDate?, today: LocalDate): Double {
    if (deadline == null) return 0.0
    val days = ChronoUnit.DAYS.between(today, deadline)
    return when {
        days <= 0 -> 5.0
        days <= 3 -> 3.0
        days <= 7 -> 2.0
        days <= 14 -> 1.0
        else -> 0.0
    }
}

internal fun rescheduleNudge(rescheduleCount: Int): Double =
    minOf(rescheduleCount, 3) * 0.5

internal fun ageNudge(createdOn: LocalDate, today: LocalDate): Double =
    if (ChronoUnit.DAYS.between(createdOn, today) > 30) 0.5 else 0.0
