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

    /**
     * Planner-facing bridge: resolves the user's sole board. Goes away when the planner becomes
     * board-aware (Phase 1 PR 3, `docs/BOARD-SHARING-PHASE1.md`).
     */
    fun getAllForUser(userId: UUID): List<BacklogTaskCategory> =
        getCategories(userId, boardMembershipService.resolveDefaultBoard(userId))

    fun getCategories(userId: UUID, boardId: UUID): List<BacklogTaskCategory> {
        boardMembershipService.requireMember(userId, boardId)
        return categoryRepository.findAllByBoardId(boardId).map { it.toDomain() }
    }

    fun createCategory(userId: UUID, boardId: UUID, request: CreateCategoryRequest): BacklogTaskCategory {
        boardMembershipService.requireMember(userId, boardId)
        val entity = BacklogTaskCategoryEntity().apply {
            this.boardId = boardId
            this.label = request.label
            this.swatchId = CategoryColor.valueOf(request.swatchId.uppercase())
        }
        return categoryRepository.save(entity).toDomain()
    }

    fun updateCategory(userId: UUID, boardId: UUID, id: UUID, request: UpdateCategoryRequest): BacklogTaskCategory {
        boardMembershipService.requireMember(userId, boardId)
        val entity = categoryRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Category $id not found")
        entity.label = request.label
        entity.swatchId = CategoryColor.valueOf(request.swatchId.uppercase())
        return categoryRepository.save(entity).toDomain()
    }

    fun deleteCategory(userId: UUID, boardId: UUID, id: UUID) {
        boardMembershipService.requireMember(userId, boardId)
        if (taskRepository.existsByCategoryIdAndBoardId(id, boardId)) {
            throw IllegalStateException("Category is in use by one or more tasks")
        }
        categoryRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Category $id not found")
        categoryRepository.deleteById(id)
    }
}
