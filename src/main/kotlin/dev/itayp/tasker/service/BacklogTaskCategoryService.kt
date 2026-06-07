package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.request.CreateCategoryRequest
import dev.itayp.tasker.model.request.UpdateCategoryRequest
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class BacklogTaskCategoryService(
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val taskRepository: BacklogTaskRepository,
    private val boardMembershipService: BoardMembershipService,
) {

    fun getAllForUser(userId: UUID): List<BacklogTaskCategory> {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        return categoryRepository.findAllByBoardId(boardId).map { it.toDomain() }
    }

    fun createCategory(userId: UUID, request: CreateCategoryRequest): BacklogTaskCategory {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entity = BacklogTaskCategoryEntity().apply {
            this.boardId = boardId
            this.label = request.label
            this.swatchId = CategoryColor.valueOf(request.swatchId.uppercase())
        }
        return categoryRepository.save(entity).toDomain()
    }

    fun updateCategory(userId: UUID, id: UUID, request: UpdateCategoryRequest): BacklogTaskCategory {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entity = categoryRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Category $id not found")
        entity.label = request.label
        entity.swatchId = CategoryColor.valueOf(request.swatchId.uppercase())
        return categoryRepository.save(entity).toDomain()
    }

    fun deleteCategory(userId: UUID, id: UUID) {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        if (taskRepository.existsByCategoryIdAndBoardId(id, boardId)) {
            throw IllegalStateException("Category is in use by one or more tasks")
        }
        categoryRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Category $id not found")
        categoryRepository.deleteById(id)
    }
}
