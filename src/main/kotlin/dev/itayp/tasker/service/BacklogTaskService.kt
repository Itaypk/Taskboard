package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.ReorderTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val REBALANCE_KEY_LENGTH_THRESHOLD = 50

@Service
class BacklogTaskService(
    private val backlogTaskRepository: BacklogTaskRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val tagRepository: BacklogTaskTagRepository,
    private val taskChangeService: BacklogTaskChangeService,
    private val userSettingsService: UserSettingsService,
    private val userCrypto: UserCryptoService,
    private val clock: Clock,
) {

    fun getAllTasksForUser(userId: UUID): List<BacklogTask> =
        backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId).map { it.toDomain(userCrypto) }

    fun getTasksForUser(userId: UUID, status: TaskStatus?): List<BacklogTask> {
        val entities = when (status) {
            null -> backlogTaskRepository.findAllByUserIdAndStatusNotOrderBySortKeyAsc(userId, TaskStatus.ARCHIVED)
            else -> backlogTaskRepository.findAllByUserIdAndStatusOrderBySortKeyAsc(userId, status)
        }
        val tasks = entities.map { it.toDomain(userCrypto) }
        // Hide future-dated tasks from the To Do view; all other statuses show them regardless.
        return if (status == TaskStatus.TODO) {
            val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
                .getOrDefault(ZoneId.of("UTC"))
            val today = LocalDate.ofInstant(clock.instant(), zone)
            tasks.filter { it.relevantFrom == null || !it.relevantFrom.isAfter(today) }
        } else tasks
    }

    fun getTaskById(userId: UUID, id: UUID): BacklogTask? =
        backlogTaskRepository.findByIdAndUserId(id, userId)?.toDomain(userCrypto)

    @Transactional(readOnly = true)
    fun getTasksScheduledInSession(userId: UUID, sessionId: UUID): List<BacklogTask> =
        backlogTaskRepository
            .findAllByUserIdAndLastScheduledInSessionIdOrderBySortKeyAsc(userId, sessionId)
            .map { it.toDomain(userCrypto) }

    @Transactional
    fun stampPlanningSession(userId: UUID, taskIds: List<UUID>, sessionId: UUID) {
        val entities = backlogTaskRepository.findAllByUserIdAndIdIn(userId, taskIds)
        for (entity in entities) {
            entity.lastScheduledInSessionId = sessionId
        }
        backlogTaskRepository.saveAll(entities)
    }

    @Transactional
    fun createTask(userId: UUID, request: CreateBacklogTaskRequest): BacklogTask {
        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndUserId(categoryId, userId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val sortKey = computeAppendKey(userId)

        val entity = BacklogTaskEntity().apply {
            this.userId = userId
            this.title = userCrypto.encrypt(userId, request.title)
            this.description = userCrypto.encrypt(userId, request.description)
            this.url = request.url
            this.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
            this.deadline = request.deadline?.let { LocalDate.parse(it) }
            this.estimatedMinutes = request.estimatedMinutes
            this.status = TaskStatus.valueOf(request.status.uppercase())
            this.category = category
            this.tags = resolveOrCreateTags(userId, request.tags)
            this.sortKey = sortKey
            this.createdAt = Instant.now()
            this.updatedAt = null
            this.relevantFrom = request.relevantFrom?.let { LocalDate.parse(it) }
        }

        val saved = backlogTaskRepository.save(entity)
        taskChangeService.recordCreated(userId, saved.id!!, request.title, saved.status!!)
        return saved.toDomain(userCrypto)
    }

    @Transactional
    fun updateTask(userId: UUID, id: UUID, request: UpdateBacklogTaskRequest): BacklogTask {
        val entity = backlogTaskRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Task $id not found")

        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndUserId(categoryId, userId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val previousStatus = entity.status!!
        val newStatus = TaskStatus.valueOf(request.status.uppercase())

        entity.title = userCrypto.encrypt(userId, request.title)
        entity.description = userCrypto.encrypt(userId, request.description)
        entity.url = request.url
        entity.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
        entity.deadline = request.deadline?.let { LocalDate.parse(it) }
        entity.estimatedMinutes = request.estimatedMinutes
        entity.status = newStatus
        entity.category = category
        entity.tags = resolveOrCreateTags(userId, request.tags)
        entity.relevantFrom = request.relevantFrom?.let { LocalDate.parse(it) }
        entity.updatedAt = Instant.now()

        val saved = backlogTaskRepository.save(entity)
        taskChangeService.recordStatusChange(userId, saved.id!!, request.title, previousStatus, newStatus)
        return saved.toDomain(userCrypto)
    }

    @Transactional
    fun reorderTask(userId: UUID, taskId: UUID, request: ReorderTaskRequest): BacklogTask {
        val entity = backlogTaskRepository.findByIdAndUserId(taskId, userId)
            ?: throw NoSuchElementException("Task $taskId not found")

        val allTasks = backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId)

        val afterKey: String? = request.afterId?.let { afterId ->
            val afterUUID = UUID.fromString(afterId)
            allTasks.find { it.id == afterUUID }?.sortKey
                ?: throw NoSuchElementException("Task $afterId not found")
        }
        val beforeKey: String? = request.beforeId?.let { beforeId ->
            val beforeUUID = UUID.fromString(beforeId)
            allTasks.find { it.id == beforeUUID }?.sortKey
                ?: throw NoSuchElementException("Task $beforeId not found")
        }

        val newSortKey = when {
            afterKey == null && beforeKey == null -> SortKeyGenerator.INITIAL
            afterKey == null -> SortKeyGenerator.before(beforeKey!!)
            beforeKey == null -> SortKeyGenerator.after(afterKey)
            else -> SortKeyGenerator.midpoint(afterKey, beforeKey)
        }

        entity.sortKey = newSortKey

        val saved = backlogTaskRepository.save(entity)

        // Rebalance lazily if any key in this user's list has grown too long.
        if (newSortKey.length > REBALANCE_KEY_LENGTH_THRESHOLD ||
            allTasks.any { (it.sortKey?.length ?: 0) > REBALANCE_KEY_LENGTH_THRESHOLD }
        ) {
            rebalanceKeys(userId)
            // Re-fetch after rebalance to return the fresh sort key.
            return (backlogTaskRepository.findByIdAndUserId(taskId, userId) ?: saved).toDomain(userCrypto)
        }

        return saved.toDomain(userCrypto)
    }

    @Transactional
    fun clearPlanningSessionStamp(userId: UUID, taskIds: List<UUID>) {
        val entities = backlogTaskRepository.findAllByUserIdAndIdIn(userId, taskIds)
        for (entity in entities) {
            entity.lastScheduledInSessionId = null
            entity.updatedAt = Instant.now()
        }
        backlogTaskRepository.saveAll(entities)
    }

    @Transactional
    fun unscheduleTask(userId: UUID, id: UUID) {
        val entity = backlogTaskRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Task $id not found")
        entity.lastScheduledInSessionId = null
        entity.updatedAt = Instant.now()
        backlogTaskRepository.save(entity)
    }

    @Transactional
    fun deleteTask(userId: UUID, id: UUID) {
        val entity = backlogTaskRepository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Task $id not found")
        val title = userCrypto.decrypt(userId, entity.title) ?: ""
        val status = entity.status!!
        backlogTaskRepository.delete(entity)
        taskChangeService.recordDeleted(userId, id, title, status)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Computes a sort key that goes after all existing tasks for the user. */
    private fun computeAppendKey(userId: UUID): String {
        val maxKey = backlogTaskRepository.findMaxSortKeyByUserId(userId)
        return if (maxKey == null) SortKeyGenerator.INITIAL else SortKeyGenerator.after(maxKey)
    }

    /**
     * Reassigns evenly-spaced sort keys to all of the user's tasks.
     * Called lazily when any key exceeds [REBALANCE_KEY_LENGTH_THRESHOLD].
     */
    private fun rebalanceKeys(userId: UUID) {
        val tasks = backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId)
        val freshKeys = SortKeyGenerator.spreadKeys(tasks.size)
        tasks.zip(freshKeys).forEach { (task, key) -> task.sortKey = key }
        backlogTaskRepository.saveAll(tasks)
    }

    private fun resolveOrCreateTags(userId: UUID, inputs: List<TagInput>): MutableSet<BacklogTaskTagEntity> {
        val existing = tagRepository.findAllByUserId(userId)

        return inputs.map { input ->
            val inputId = input.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            
            // Try to find by exact ID first
            val matchById = inputId?.let { id -> existing.find { it.id == id } }
            if (matchById != null) {
                return@map matchById
            }
            
            // Fallback to case-insensitive label match
            val matchByLabel = existing.find { it.label?.equals(input.label.trim(), ignoreCase = true) == true }
            if (matchByLabel != null) {
                return@map matchByLabel
            }
            
            // Create new
            BacklogTaskTagEntity().apply {
                this.userId = userId
                this.label = input.label.trim()
                this.colorId = TagColor.valueOf(input.colorId.uppercase())
            }.let { tagRepository.save(it) }
        }.toMutableSet()
    }
}
