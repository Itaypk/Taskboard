package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BacklogTaskRepository : JpaRepository<BacklogTaskEntity, UUID> {

    fun findAllByUserIdOrderBySortKeyAsc(userId: UUID): List<BacklogTaskEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): BacklogTaskEntity?

    fun existsByCategoryIdAndUserId(categoryId: UUID, userId: UUID): Boolean

    fun deleteAllByUserId(userId: UUID)

    @Query("SELECT MAX(t.sortKey) FROM BacklogTaskEntity t WHERE t.userId = :userId")
    fun findMaxSortKeyByUserId(userId: UUID): String?
}
