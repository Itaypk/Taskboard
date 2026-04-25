package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BacklogTaskCategoryRepository : JpaRepository<BacklogTaskCategoryEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<BacklogTaskCategoryEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): BacklogTaskCategoryEntity?

    fun deleteAllByUserId(userId: UUID)
}
