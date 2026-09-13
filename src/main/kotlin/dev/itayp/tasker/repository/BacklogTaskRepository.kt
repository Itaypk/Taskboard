package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.TaskStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
interface BacklogTaskRepository : JpaRepository<BacklogTaskEntity, UUID> {

    fun findAllByBoardIdOrderBySortKeyAsc(boardId: UUID): List<BacklogTaskEntity>

    fun findAllByBoardIdAndStatus(boardId: UUID, status: TaskStatus): List<BacklogTaskEntity>

    /** Global count for metrics; not board-scoped. */
    fun countByStatus(status: TaskStatus): Long

    fun countByBoardIdAndStatus(boardId: UUID, status: TaskStatus): Long

    fun findAllByBoardIdAndStatusOrderBySortKeyAsc(boardId: UUID, status: TaskStatus): List<BacklogTaskEntity>

    fun findAllByBoardIdAndStatusNotOrderBySortKeyAsc(boardId: UUID, status: TaskStatus): List<BacklogTaskEntity>

    fun findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(
        boardId: UUID,
        status: TaskStatus,
        before: Instant,
    ): List<BacklogTaskEntity>

    fun findAllByBoardIdAndLastScheduledInSessionIdOrderBySortKeyAsc(
        boardId: UUID,
        lastScheduledInSessionId: UUID,
    ): List<BacklogTaskEntity>

    fun findAllByBoardIdAndIdIn(boardId: UUID, ids: List<UUID>): List<BacklogTaskEntity>

    fun findByIdAndBoardId(id: UUID, boardId: UUID): BacklogTaskEntity?

    fun findAllByBoardIdAndTutorialTrue(boardId: UUID): List<BacklogTaskEntity>

    // --- Cross-board lookups (the planner spans every board the user belongs to) ---

    fun findByIdAndBoardIdIn(id: UUID, boardIds: Collection<UUID>): BacklogTaskEntity?

    fun findAllByBoardIdInAndIdIn(boardIds: Collection<UUID>, ids: Collection<UUID>): List<BacklogTaskEntity>

    fun findAllByBoardIdInAndStatusOrderBySortKeyAsc(
        boardIds: Collection<UUID>,
        status: TaskStatus,
    ): List<BacklogTaskEntity>

    fun findAllByBoardIdInAndStatusNotOrderBySortKeyAsc(
        boardIds: Collection<UUID>,
        status: TaskStatus,
    ): List<BacklogTaskEntity>

    fun findAllByBoardIdInAndLastScheduledInSessionIdOrderBySortKeyAsc(
        boardIds: Collection<UUID>,
        lastScheduledInSessionId: UUID,
    ): List<BacklogTaskEntity>

    fun existsByCategoryIdAndBoardId(categoryId: UUID, boardId: UUID): Boolean

    fun deleteAllByBoardId(boardId: UUID)

    /** All tasks on the board carrying [tagId] — used to detach a tag before deleting it. */
    @Query("SELECT t FROM BacklogTaskEntity t JOIN t.tags tag WHERE tag.id = :tagId")
    fun findAllByTagId(tagId: UUID): List<BacklogTaskEntity>

    /** `[tagId, taskCount]` rows for every tag actually used on the board; unused tags are absent. */
    @Query("SELECT tag.id, COUNT(t.id) FROM BacklogTaskEntity t JOIN t.tags tag WHERE t.boardId = :boardId GROUP BY tag.id")
    fun countTasksPerTag(boardId: UUID): List<Array<Any>>

    @Query("SELECT MAX(t.sortKey) FROM BacklogTaskEntity t WHERE t.boardId = :boardId")
    fun findMaxSortKeyByBoardId(boardId: UUID): String?

    /**
     * A recurring task completed during or after the plan's week stays TODO (it rolled forward) and
     * keeps its session stamp, so [weekStart] is what tells it apart from genuine carry-over.
     */
    @Modifying
    @Query(
        """
        UPDATE BacklogTaskEntity t
        SET t.rescheduleCount = t.rescheduleCount + 1
        WHERE t.boardId = :boardId
          AND t.status = dev.itayp.tasker.model.TaskStatus.TODO
          AND t.lastScheduledInSessionId = :sessionId
          AND (t.lastCompletedOn IS NULL OR t.lastCompletedOn < :weekStart)
        """
    )
    fun incrementRescheduleCountForUnfinishedTasks(boardId: UUID, sessionId: UUID, weekStart: LocalDate): Int

    /** Clears claims a departing member holds on a board's tasks — no ghost assignees behind them. */
    @Modifying
    @Query("UPDATE BacklogTaskEntity t SET t.assigneeUserId = NULL WHERE t.boardId = :boardId AND t.assigneeUserId = :userId")
    fun clearAssigneeOnBoardForUser(boardId: UUID, userId: UUID): Int
}
