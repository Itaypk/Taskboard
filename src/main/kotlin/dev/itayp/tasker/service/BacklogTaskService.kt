package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class BacklogTaskService(
    private val backlogTaskRepository: BacklogTaskRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val tagRepository: BacklogTaskTagRepository
) {

    fun getAllTasksForUser(userId: UUID): List<BacklogTask> =
        backlogTaskRepository.findAllByUserId(userId).map { it.toDomain() }

    @Transactional
    fun createTask(userId: UUID, request: CreateBacklogTaskRequest): BacklogTask {
        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndUserId(categoryId, userId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val entity = BacklogTaskEntity().apply {
            this.userId = userId
            this.title = request.title
            this.description = request.description
            this.url = request.url
            this.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
            this.deadline = request.deadline?.let { LocalDate.parse(it) }
            this.estimatedMinutes = request.estimatedMinutes
            this.status = TaskStatus.valueOf(request.status.uppercase())
            this.category = category
            this.tags = resolveOrCreateTags(userId, request.tags)
            this.createdAt = Instant.now()
            this.updatedAt = null
        }

        return backlogTaskRepository.save(entity).toDomain()
    }

    @Transactional
    fun updateTask(userId: UUID, id: UUID, request: UpdateBacklogTaskRequest): BacklogTask {
        val entity = backlogTaskRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Task $id not found")

        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndUserId(categoryId, userId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        entity.title = request.title
        entity.description = request.description
        entity.url = request.url
        entity.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
        entity.deadline = request.deadline?.let { LocalDate.parse(it) }
        entity.estimatedMinutes = request.estimatedMinutes
        entity.status = TaskStatus.valueOf(request.status.uppercase())
        entity.category = category
        entity.tags = resolveOrCreateTags(userId, request.tags)
        entity.updatedAt = Instant.now()

        return backlogTaskRepository.save(entity).toDomain()
    }

    fun deleteTask(userId: UUID, id: UUID) {
        val entity = backlogTaskRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Task $id not found")
        backlogTaskRepository.delete(entity)
    }

    private fun resolveOrCreateTags(userId: UUID, inputs: List<TagInput>): MutableSet<BacklogTaskTagEntity> {
        val existing = tagRepository.findAllByUserId(userId)
        val lookup = existing.associateBy { "${it.label}::${it.colorId?.name}" }

        return inputs.map { input ->
            val key = "${input.label}::${input.colorId.uppercase()}"
            lookup[key] ?: BacklogTaskTagEntity().apply {
                this.userId = userId
                this.label = input.label
                this.colorId = TagColor.valueOf(input.colorId.uppercase())
            }.let { tagRepository.save(it) }
        }.toMutableSet()
    }
}
