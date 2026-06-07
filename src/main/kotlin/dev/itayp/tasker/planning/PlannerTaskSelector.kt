package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.service.BoardMembershipService
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
    private val boardCrypto: BoardCryptoService,
    private val boardMembershipService: BoardMembershipService,
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
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val tasks = backlogTaskRepository
            .findAllByBoardIdAndStatus(boardId, TaskStatus.TODO)
            .map { it.toDomain(boardCrypto) }
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
        val plannedElsewhere = lookupPlannedElsewhere(userId, selectedIds, weekStart, zone)

        return PlannerTaskSelection(
            urgent = urgent,
            stale = stale,
            alreadyPlanned = plannedElsewhere.future,
            alreadyScheduled = plannedElsewhere.earlier,
        )
    }

    /**
     * For each candidate task, find its existing planned slots that fall *outside* the planning
     * window, split into two buckets the prompt annotates differently:
     *  - [PlannedElsewhere.future]: the earliest slot strictly after the window — the task is already
     *    committed to a later week, so don't re-schedule it here unless pulling it forward.
     *  - [PlannedElsewhere.earlier]: the latest slot strictly before the window — the task already has
     *    (or had) a slot in an active/earlier plan it hasn't completed. When planning next week this
     *    is the current week's plan; flag it so the assistant doesn't double-book a task the user is
     *    already working through.
     * Slots inside the window are ignored (that's the week being planned).
     */
    private fun lookupPlannedElsewhere(
        userId: UUID,
        taskIds: List<UUID>,
        weekStart: LocalDate,
        zone: ZoneId,
    ): PlannedElsewhere {
        if (taskIds.isEmpty()) return PlannedElsewhere(emptyMap(), emptyMap())
        val plannedTasks = plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(userId, taskIds)
        if (plannedTasks.isEmpty()) return PlannedElsewhere(emptyMap(), emptyMap())

        val plannedTaskById = plannedTasks.associateBy { requireNotNull(it.id) }
        val slots = plannedTaskSlotRepository.findAllByPlannedTaskIdIn(plannedTaskById.keys)
        if (slots.isEmpty()) return PlannedElsewhere(emptyMap(), emptyMap())

        // Window boundaries as local dates in the user's zone.
        val windowStart = weekStart
        val windowLastDay = weekStart.plusDays(6)
        val future = mutableMapOf<UUID, LocalDate>()
        val earlier = mutableMapOf<UUID, LocalDate>()
        for (slot in slots) {
            val backlogTaskId = plannedTaskById[slot.plannedTaskId]?.backlogTaskId ?: continue
            val startIso = slot.startIso ?: continue
            val slotDate = runCatching { OffsetDateTime.parse(startIso).atZoneSameInstant(zone).toLocalDate() }
                .getOrNull() ?: continue
            when {
                slotDate.isAfter(windowLastDay) -> {
                    // earliest upcoming commitment
                    val existing = future[backlogTaskId]
                    if (existing == null || slotDate.isBefore(existing)) future[backlogTaskId] = slotDate
                }
                slotDate.isBefore(windowStart) -> {
                    // most recent prior commitment
                    val existing = earlier[backlogTaskId]
                    if (existing == null || slotDate.isAfter(existing)) earlier[backlogTaskId] = slotDate
                }
                // within the planning window: this is the week being planned — ignore.
            }
        }
        return PlannedElsewhere(future, earlier)
    }

    private data class PlannedElsewhere(
        val future: Map<UUID, LocalDate>,
        val earlier: Map<UUID, LocalDate>,
    )

    companion object {
        const val DEFAULT_URGENT_SLOTS = 12
        const val DEFAULT_STALE_SLOTS = 3
        private const val STALE_FRESHNESS_DAYS = 7L
    }
}

data class PlannerTaskSelection(
    val urgent: List<BacklogTask>,
    val stale: List<BacklogTask>,
    /** Candidates already scheduled in a *later* week, mapped to their earliest upcoming slot date. */
    val alreadyPlanned: Map<UUID, LocalDate> = emptyMap(),
    /** Candidates already scheduled in an *earlier* (e.g. the current) plan, mapped to the latest such slot date. */
    val alreadyScheduled: Map<UUID, LocalDate> = emptyMap(),
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
