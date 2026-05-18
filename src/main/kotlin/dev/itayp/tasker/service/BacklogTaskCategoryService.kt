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
    private val taskRepository: BacklogTaskRepository
) {

    fun getAllForUser(userId: UUID): List<BacklogTaskCategory> =
        categoryRepository.findAllByUserId(userId).map { it.toDomain() }

    fun createCategory(userId: UUID, request: CreateCategoryRequest): BacklogTaskCategory {
        val entity = BacklogTaskCategoryEntity().apply {
            this.userId = userId
            this.label = request.label
            this.swatchId = CategoryColor.valueOf(request.swatchId.uppercase())
        }
        return categoryRepository.save(entity).toDomain()
    }

    fun updateCategory(userId: UUID, id: UUID, request: UpdateCategoryRequest): BacklogTaskCategory {
        val entity = categoryRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Category $id not found")
        entity.label = request.label
        entity.swatchId = CategoryColor.valueOf(request.swatchId.uppercase())
        return categoryRepository.save(entity).toDomain()
    }

    fun deleteCategory(userId: UUID, id: UUID) {
        if (taskRepository.existsByCategoryIdAndUserId(id, userId)) {
            throw IllegalStateException("Category is in use by one or more tasks")
        }
        categoryRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Category $id not found")
        categoryRepository.deleteById(id)
    }
}
