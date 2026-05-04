package dev.itayp.tasker.planning

import dev.itayp.tasker.model.TaskStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Records task lifecycle events (creation, status change, deletion) for use by the
 * weekly planner. Events are append-only and outlive the underlying task row, so
 * inter-session diffs remain accurate even after a task is deleted.
 */
@Service
class BacklogTaskChangeService(
    private val eventRepository: BacklogTaskChangeEventRepository,
    private val clock: Clock,
) {

    @Transactional
    fun recordCreated(userId: UUID, taskId: UUID, title: String, status: TaskStatus): BacklogTaskChangeEventEntity =
        eventRepository.save(BacklogTaskChangeEventEntity().apply {
            this.userId = userId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.CREATED
            this.previousStatus = null
            this.newStatus = status
            this.taskTitleSnapshot = title
            this.occurredAt = clock.instant()
        })

    @Transactional
    fun recordStatusChange(
        userId: UUID,
        taskId: UUID,
        title: String,
        previousStatus: TaskStatus,
        newStatus: TaskStatus,
    ): BacklogTaskChangeEventEntity? {
        if (previousStatus == newStatus) return null
        return eventRepository.save(BacklogTaskChangeEventEntity().apply {
            this.userId = userId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.STATUS_CHANGED
            this.previousStatus = previousStatus
            this.newStatus = newStatus
            this.taskTitleSnapshot = title
            this.occurredAt = clock.instant()
        })
    }

    @Transactional
    fun recordDeleted(userId: UUID, taskId: UUID, title: String, lastStatus: TaskStatus): BacklogTaskChangeEventEntity =
        eventRepository.save(BacklogTaskChangeEventEntity().apply {
            this.userId = userId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.DELETED
            this.previousStatus = lastStatus
            this.newStatus = null
            this.taskTitleSnapshot = title
            this.occurredAt = clock.instant()
        })

    /**
     * Summarises task changes that happened since [since] (inclusive) up to now.
     * Used by the planner to brief the next session on what moved during the week.
     */
    @Transactional(readOnly = true)
    fun summarizeSince(userId: UUID, since: Instant): TaskChangeSummary {
        val events = eventRepository
            .findAllByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(userId, since)
        return summarize(events)
    }

    @Transactional(readOnly = true)
    fun summarizeBetween(userId: UUID, from: Instant, to: Instant): TaskChangeSummary {
        val events = eventRepository
            .findAllByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(userId, from, to)
        return summarize(events)
    }

    private fun summarize(events: List<BacklogTaskChangeEventEntity>): TaskChangeSummary {
        val createdTaskIds = mutableSetOf<UUID>()
        val completed = mutableMapOf<UUID, ChangedTask>()
        val reopened = mutableMapOf<UUID, ChangedTask>()
        val deleted = mutableMapOf<UUID, ChangedTask>()
        val createdInWindow = mutableMapOf<UUID, ChangedTask>()

        for (event in events) {
            val taskId = event.taskId ?: continue
            val title = event.taskTitleSnapshot ?: ""
            when (event.changeType) {
                BacklogTaskChangeType.CREATED -> {
                    createdTaskIds.add(taskId)
                    createdInWindow[taskId] = ChangedTask(taskId, title)
                }
                BacklogTaskChangeType.STATUS_CHANGED -> {
                    val changed = ChangedTask(taskId, title)
                    if (event.newStatus == TaskStatus.DONE) {
                        completed[taskId] = changed
                        reopened.remove(taskId)
                    } else if (event.newStatus == TaskStatus.TODO && event.previousStatus == TaskStatus.DONE) {
                        reopened[taskId] = changed
                        completed.remove(taskId)
                    }
                }
                BacklogTaskChangeType.DELETED -> {
                    deleted[taskId] = ChangedTask(taskId, title)
                    completed.remove(taskId)
                    reopened.remove(taskId)
                    createdInWindow.remove(taskId)
                }
                null -> Unit
            }
        }

        val completedAddedDuringWindow = completed.keys.intersect(createdTaskIds)
            .mapNotNull { completed[it] }
        val completedFromBacklog = completed.filterKeys { it !in createdTaskIds }.values.toList()

        return TaskChangeSummary(
            createdDuringWindow = createdInWindow.values.toList(),
            completed = completed.values.toList(),
            completedFromBacklog = completedFromBacklog,
            completedAddedDuringWindow = completedAddedDuringWindow,
            reopened = reopened.values.toList(),
            deleted = deleted.values.toList(),
            totalEvents = events.size,
        )
    }
}

data class ChangedTask(
    val taskId: UUID,
    val title: String,
)

/**
 * Compact view of what happened to the user's backlog within a window.
 * Designed to be serialised verbatim into the next planning session's prompt.
 */
data class TaskChangeSummary(
    val createdDuringWindow: List<ChangedTask>,
    val completed: List<ChangedTask>,
    val completedFromBacklog: List<ChangedTask>,
    val completedAddedDuringWindow: List<ChangedTask>,
    val reopened: List<ChangedTask>,
    val deleted: List<ChangedTask>,
    val totalEvents: Int,
)
