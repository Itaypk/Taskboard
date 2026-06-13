package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.BoardCryptoService
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
 *
 * Both the events and the "tasks changed" watermark are keyed by **board** so a member's edit
 * surfaces to other members; title snapshots are encrypted under the **board DEK** so a feed entry
 * survives its author leaving the board. Each event also carries an [actorUserId] — "who did it" —
 * which backs personal stats and is nulled (not deleted) when the actor's account is removed.
 */
@Service
class BacklogTaskChangeService(
    private val eventRepository: BacklogTaskChangeEventRepository,
    private val watermarkRepository: BacklogTaskWatermarkRepository,
    private val boardCrypto: BoardCryptoService,
    private val clock: Clock,
) {

    @Transactional
    fun recordCreated(
        boardId: UUID,
        actorUserId: UUID,
        taskId: UUID,
        title: String,
        status: TaskStatus,
    ): BacklogTaskChangeEventEntity =
        eventRepository.save(BacklogTaskChangeEventEntity().apply {
            this.boardId = boardId
            this.actorUserId = actorUserId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.CREATED
            this.previousStatus = null
            this.newStatus = status
            this.taskTitleSnapshot = boardCrypto.encrypt(boardId, title)
            this.occurredAt = clock.instant()
        })

    @Transactional
    fun recordStatusChange(
        boardId: UUID,
        actorUserId: UUID,
        taskId: UUID,
        title: String,
        previousStatus: TaskStatus,
        newStatus: TaskStatus,
    ): BacklogTaskChangeEventEntity? {
        if (previousStatus == newStatus) return null
        return eventRepository.save(BacklogTaskChangeEventEntity().apply {
            this.boardId = boardId
            this.actorUserId = actorUserId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.STATUS_CHANGED
            this.previousStatus = previousStatus
            this.newStatus = newStatus
            this.taskTitleSnapshot = boardCrypto.encrypt(boardId, title)
            this.occurredAt = clock.instant()
        })
    }

    @Transactional
    fun recordDeleted(
        boardId: UUID,
        actorUserId: UUID,
        taskId: UUID,
        title: String,
        lastStatus: TaskStatus,
    ): BacklogTaskChangeEventEntity =
        eventRepository.save(BacklogTaskChangeEventEntity().apply {
            this.boardId = boardId
            this.actorUserId = actorUserId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.DELETED
            this.previousStatus = lastStatus
            this.newStatus = null
            this.taskTitleSnapshot = boardCrypto.encrypt(boardId, title)
            this.occurredAt = clock.instant()
        })

    /**
     * Summarises task changes across [boardIds] that happened since [since] (inclusive) up to now.
     * Used by the planner to brief the next session on what moved during the week — the union of
     * every board the user belongs to. Each task lives on exactly one board, so concatenating the
     * boards' events preserves per-task ordering.
     */
    @Transactional(readOnly = true)
    fun summarizeSince(boardIds: Collection<UUID>, since: Instant): TaskChangeSummary {
        if (boardIds.isEmpty()) return EMPTY_SUMMARY
        val events = eventRepository
            .findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since)
        return summarize(events)
    }

    /**
     * Bumps the board's lightweight "tasks changed" watermark to now. Call from every backlog
     * mutation — including plain field edits and reorders that don't warrant a semantic
     * [BacklogTaskChangeEventEntity]. Backs [changedSince] (the polling endpoint).
     */
    @Transactional
    fun bumpWatermark(boardId: UUID) {
        watermarkRepository.save(BacklogTaskWatermarkEntity().apply {
            this.boardId = boardId
            this.tasksChangedAt = clock.instant()
        })
    }

    /**
     * Cheap O(1) check used by the polling endpoint: has any task on [boardId] changed at or after
     * [since]? Unlike the semantic event log, this also reflects field edits, reorders, and
     * scheduling stamps, and never scans the tasks table.
     */
    @Transactional(readOnly = true)
    fun changedSince(boardId: UUID, since: Instant): Boolean =
        watermarkRepository.existsByBoardIdAndTasksChangedAtGreaterThanEqual(boardId, since)

    private fun summarize(events: List<BacklogTaskChangeEventEntity>): TaskChangeSummary {
        val createdTaskIds = mutableSetOf<UUID>()
        val completed = mutableMapOf<UUID, ChangedTask>()
        val reopened = mutableMapOf<UUID, ChangedTask>()
        val deleted = mutableMapOf<UUID, ChangedTask>()
        val createdInWindow = mutableMapOf<UUID, ChangedTask>()

        for (event in events) {
            val taskId = event.taskId ?: continue
            val boardId = event.boardId ?: continue
            val title = boardCrypto.decrypt(boardId, event.taskTitleSnapshot) ?: ""
            when (event.changeType) {
                BacklogTaskChangeType.CREATED -> {
                    createdTaskIds.add(taskId)
                    createdInWindow[taskId] = ChangedTask(taskId, title)
                }
                BacklogTaskChangeType.STATUS_CHANGED -> {
                    val changed = ChangedTask(taskId, title)
                    when {
                        event.newStatus == TaskStatus.DONE -> {
                            completed[taskId] = changed
                            reopened.remove(taskId)
                        }
                        event.newStatus == TaskStatus.TODO && event.previousStatus != TaskStatus.TODO -> {
                            reopened[taskId] = changed
                            completed.remove(taskId)
                        }
                        // DONE → ARCHIVED: silent, already counted as completed
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

    companion object {
        val EMPTY_SUMMARY = TaskChangeSummary(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0,
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
