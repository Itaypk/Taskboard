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
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Picks a bounded slate of TODO tasks for the LLM weekly planner. Returns two named
 * pools so the prompt template can label them: "urgent" (priority + deadline +
 * reschedule history) and "stale" (forgotten, not deferred). The LLM reasons over
 * both; we never send the full backlog.
 */
@Service
class PlannerTaskSelector(
    private val backlogTaskRepository: BacklogTaskRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun select(
        userId: UUID,
        urgentSlots: Int = DEFAULT_URGENT_SLOTS,
        staleSlots: Int = DEFAULT_STALE_SLOTS,
    ): PlannerTaskSelection {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val tasks = backlogTaskRepository
            .findAllByUserIdAndStatus(userId, TaskStatus.TODO)
            .map { it.toDomain() }
            .filter { it.relevantFrom == null || !it.relevantFrom.isAfter(today) }

        val totalSlots = urgentSlots + staleSlots
        if (tasks.size <= totalSlots) {
            return PlannerTaskSelection(
                urgent = tasks.sortedByDescending { urgencyScore(it, clock) },
                stale = emptyList(),
            )
        }

        val urgent = tasks
            .sortedByDescending { urgencyScore(it, clock) }
            .take(urgentSlots)
        val urgentIds = urgent.mapTo(mutableSetOf()) { it.id }

        val now = clock.instant()
        val freshnessCutoff = now.minus(STALE_FRESHNESS_DAYS, ChronoUnit.DAYS)

        val stale = tasks
            .asSequence()
            .filter { it.id !in urgentIds }
            .filter { it.rescheduleCount == 0 }
            .filter { (it.updatedAt ?: it.createdAt).isBefore(freshnessCutoff) }
            .sortedBy { it.updatedAt ?: it.createdAt }
            .take(staleSlots)
            .toList()

        return PlannerTaskSelection(urgent = urgent, stale = stale)
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
)

internal fun urgencyScore(task: BacklogTask, clock: Clock): Double {
    val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    return priorityWeight(task.priority) +
        deadlineUrgency(task.deadline, today) +
        rescheduleNudge(task.rescheduleCount) +
        ageNudge(task.createdAt.atZone(ZoneOffset.UTC).toLocalDate(), today)
}

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
