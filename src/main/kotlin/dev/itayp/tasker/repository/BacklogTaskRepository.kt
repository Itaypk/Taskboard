package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BacklogTaskRepository : JpaRepository<BacklogTaskEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<BacklogTaskEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): BacklogTaskEntity?

    fun existsByCategoryIdAndUserId(categoryId: UUID, userId: UUID): Boolean

    fun deleteAllByUserId(userId: UUID)
}
