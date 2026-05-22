package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.TaskStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface BacklogTaskRepository : JpaRepository<BacklogTaskEntity, UUID> {

    fun findAllByUserIdOrderBySortKeyAsc(userId: UUID): List<BacklogTaskEntity>

    fun findAllByUserIdAndStatus(userId: UUID, status: TaskStatus): List<BacklogTaskEntity>

    fun findAllByUserIdAndStatusOrderBySortKeyAsc(userId: UUID, status: TaskStatus): List<BacklogTaskEntity>

    fun findAllByUserIdAndStatusNotOrderBySortKeyAsc(userId: UUID, status: TaskStatus): List<BacklogTaskEntity>

    fun findAllByUserIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(
        userId: UUID,
        status: TaskStatus,
        before: Instant,
    ): List<BacklogTaskEntity>

    fun findAllByUserIdAndLastScheduledInSessionIdOrderBySortKeyAsc(
        userId: UUID,
        lastScheduledInSessionId: UUID,
    ): List<BacklogTaskEntity>

    fun findAllByUserIdAndIdIn(userId: UUID, ids: List<UUID>): List<BacklogTaskEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): BacklogTaskEntity?

    fun existsByCategoryIdAndUserId(categoryId: UUID, userId: UUID): Boolean

    fun deleteAllByUserId(userId: UUID)

    @Query("SELECT MAX(t.sortKey) FROM BacklogTaskEntity t WHERE t.userId = :userId")
    fun findMaxSortKeyByUserId(userId: UUID): String?

    @Modifying
    @Query(
        """
        UPDATE BacklogTaskEntity t
        SET t.rescheduleCount = t.rescheduleCount + 1
        WHERE t.userId = :userId
          AND t.status = dev.itayp.tasker.model.TaskStatus.TODO
          AND t.lastScheduledInSessionId = :sessionId
        """
    )
    fun incrementRescheduleCountForUnfinishedTasks(userId: UUID, sessionId: UUID): Int
}
