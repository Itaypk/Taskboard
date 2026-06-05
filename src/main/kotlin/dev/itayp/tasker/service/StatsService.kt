package dev.itayp.tasker.service

import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.UserStats
import dev.itayp.tasker.planning.BacklogTaskChangeEventEntity
import dev.itayp.tasker.planning.BacklogTaskChangeEventRepository
import dev.itayp.tasker.planning.BacklogTaskChangeType
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Computes a user's on-demand activity stats. Current snapshot counts come straight from the
 * tasks/sessions tables; throughput and completion-time figures are reconstructed from the
 * append-only backlog change-event log so they stay accurate even after tasks are deleted.
 */
@Service
class StatsService(
    private val userRepository: UserRepository,
    private val taskRepository: BacklogTaskRepository,
    private val sessionRepository: PlanningSessionRepository,
    private val changeEventRepository: BacklogTaskChangeEventRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun computeStats(userId: UUID): UserStats {
        val joinedAt = userRepository.findById(userId).orElse(null)?.createdAt

        val openTasks = taskRepository.countByUserIdAndStatus(userId, TaskStatus.TODO)
        val completedTasks = taskRepository.countByUserIdAndStatus(userId, TaskStatus.DONE)
        val planningSessions = sessionRepository.countByUserIdAndStatus(userId, PlanningSessionStatus.COMPLETED)

        val events = changeEventRepository.findAllByUserIdOrderByOccurredAtAsc(userId)

        val createdCount = events.count { it.changeType == BacklogTaskChangeType.CREATED }
        val createdAtByTask = firstOccurrenceByTask(events) { it.changeType == BacklogTaskChangeType.CREATED }
        val firstCompletedAtByTask = firstOccurrenceByTask(events) {
            it.changeType == BacklogTaskChangeType.STATUS_CHANGED && it.newStatus == TaskStatus.DONE
        }

        // Throughput is averaged over the recorded-activity span (first event → now) so the
        // numerator and denominator come from the same era; clamp to a week to avoid inflating
        // figures for users active for only a few days.
        val weeks = events.firstOrNull()?.occurredAt?.let { first ->
            val days = Duration.between(first, clock.instant()).toDays().coerceAtLeast(0)
            (days / 7.0).coerceAtLeast(1.0)
        } ?: 1.0

        val completionDurations = firstCompletedAtByTask.mapNotNull { (taskId, completedAt) ->
            val createdAt = createdAtByTask[taskId] ?: return@mapNotNull null
            Duration.between(createdAt, completedAt).takeIf { !it.isNegative }
        }
        val avgCompletion = completionDurations
            .takeIf { it.isNotEmpty() }
            ?.let { durations -> Duration.ofSeconds(durations.sumOf { it.seconds } / durations.size) }

        return UserStats(
            joinedAt = joinedAt,
            openTasks = openTasks,
            completedTasks = completedTasks,
            planningSessions = planningSessions,
            avgTasksCreatedPerWeek = createdCount / weeks,
            avgTasksCompletedPerWeek = firstCompletedAtByTask.size / weeks,
            avgCompletion = avgCompletion,
        )
    }

    private fun firstOccurrenceByTask(
        events: List<BacklogTaskChangeEventEntity>,
        predicate: (BacklogTaskChangeEventEntity) -> Boolean,
    ): Map<UUID, java.time.Instant> {
        val result = LinkedHashMap<UUID, java.time.Instant>()
        for (event in events) {
            if (!predicate(event)) continue
            val taskId = event.taskId ?: continue
            val occurredAt = event.occurredAt ?: continue
            result.putIfAbsent(taskId, occurredAt) // events arrive oldest-first, so first wins
        }
        return result
    }
}
