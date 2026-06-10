package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BacklogTaskCategoryRepository : JpaRepository<BacklogTaskCategoryEntity, UUID> {

    fun findAllByBoardId(boardId: UUID): List<BacklogTaskCategoryEntity>

    fun findByIdAndBoardId(id: UUID, boardId: UUID): BacklogTaskCategoryEntity?

    fun deleteAllByBoardId(boardId: UUID)
}
