package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
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
    private val boardMembershipService: BoardMembershipService,
    private val boardCrypto: BoardCryptoService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getAllTasksForUser(userId: UUID): List<BacklogTask> {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        return backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId).map { it.toDomain(boardCrypto) }
    }

    @Transactional(readOnly = true)
    fun getTasksForUser(userId: UUID, status: TaskStatus?): List<BacklogTask> {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entities = when (status) {
            null -> backlogTaskRepository.findAllByBoardIdAndStatusNotOrderBySortKeyAsc(boardId, TaskStatus.ARCHIVED)
            else -> backlogTaskRepository.findAllByBoardIdAndStatusOrderBySortKeyAsc(boardId, status)
        }
        val tasks = entities.map { it.toDomain(boardCrypto) }
        // Hide future-dated tasks from the To Do view; all other statuses show them regardless.
        return if (status == TaskStatus.TODO) {
            val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
                .getOrDefault(ZoneId.of("UTC"))
            val today = LocalDate.ofInstant(clock.instant(), zone)
            tasks.filter { it.relevantFrom == null || !it.relevantFrom.isAfter(today) }
        } else tasks
    }

    @Transactional(readOnly = true)
    fun getTaskById(userId: UUID, id: UUID): BacklogTask? {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        return backlogTaskRepository.findByIdAndBoardId(id, boardId)?.toDomain(boardCrypto)
    }

    @Transactional(readOnly = true)
    fun getTasksScheduledInSession(userId: UUID, sessionId: UUID): List<BacklogTask> {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        return backlogTaskRepository
            .findAllByBoardIdAndLastScheduledInSessionIdOrderBySortKeyAsc(boardId, sessionId)
            .map { it.toDomain(boardCrypto) }
    }

    @Transactional
    fun stampPlanningSession(userId: UUID, taskIds: List<UUID>, sessionId: UUID) {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entities = backlogTaskRepository.findAllByBoardIdAndIdIn(boardId, taskIds)
        for (entity in entities) {
            entity.lastScheduledInSessionId = sessionId
        }
        backlogTaskRepository.saveAll(entities)
        taskChangeService.bumpWatermark(userId)
    }

    @Transactional
    fun createTask(userId: UUID, request: CreateBacklogTaskRequest): BacklogTask {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndBoardId(categoryId, boardId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val sortKey = computeAppendKey(boardId)

        val entity = BacklogTaskEntity().apply {
            this.boardId = boardId
            this.title = boardCrypto.encrypt(boardId, request.title)
            this.description = boardCrypto.encrypt(boardId, request.description)
            this.url = request.url
            this.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
            this.deadline = request.deadline?.let { LocalDate.parse(it) }
            this.estimatedMinutes = request.estimatedMinutes
            this.status = TaskStatus.valueOf(request.status.uppercase())
            this.category = category
            this.tags = resolveOrCreateTags(boardId, request.tags)
            this.sortKey = sortKey
            this.createdAt = Instant.now()
            this.updatedAt = null
            this.relevantFrom = request.relevantFrom?.let { LocalDate.parse(it) }
        }

        val saved = backlogTaskRepository.save(entity)
        taskChangeService.recordCreated(userId, saved.id!!, request.title, saved.status!!)
        taskChangeService.bumpWatermark(userId)
        return saved.toDomain(boardCrypto)
    }

    @Transactional
    fun updateTask(userId: UUID, id: UUID, request: UpdateBacklogTaskRequest): BacklogTask {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entity = backlogTaskRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Task $id not found")

        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndBoardId(categoryId, boardId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val previousStatus = entity.status!!
        val newStatus = TaskStatus.valueOf(request.status.uppercase())

        entity.title = boardCrypto.encrypt(boardId, request.title)
        entity.description = boardCrypto.encrypt(boardId, request.description)
        entity.url = request.url
        entity.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
        entity.deadline = request.deadline?.let { LocalDate.parse(it) }
        entity.estimatedMinutes = request.estimatedMinutes
        entity.status = newStatus
        entity.category = category
        entity.tags = resolveOrCreateTags(boardId, request.tags)
        entity.relevantFrom = request.relevantFrom?.let { LocalDate.parse(it) }
        entity.updatedAt = Instant.now()

        val saved = backlogTaskRepository.save(entity)
        // recordStatusChange is a no-op when the status is unchanged; the watermark must still
        // bump so field edits (title/description/tags/…) surface to a polling board tab.
        taskChangeService.recordStatusChange(userId, saved.id!!, request.title, previousStatus, newStatus)
        taskChangeService.bumpWatermark(userId)
        return saved.toDomain(boardCrypto)
    }

    @Transactional
    fun reorderTask(userId: UUID, taskId: UUID, request: ReorderTaskRequest): BacklogTask {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entity = backlogTaskRepository.findByIdAndBoardId(taskId, boardId)
            ?: throw NoSuchElementException("Task $taskId not found")

        val allTasks = backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)

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
        taskChangeService.bumpWatermark(userId)

        // Rebalance lazily if any key in this board's list has grown too long.
        if (newSortKey.length > REBALANCE_KEY_LENGTH_THRESHOLD ||
            allTasks.any { (it.sortKey?.length ?: 0) > REBALANCE_KEY_LENGTH_THRESHOLD }
        ) {
            rebalanceKeys(boardId)
            // Re-fetch after rebalance to return the fresh sort key.
            return (backlogTaskRepository.findByIdAndBoardId(taskId, boardId) ?: saved).toDomain(boardCrypto)
        }

        return saved.toDomain(boardCrypto)
    }

    @Transactional
    fun clearPlanningSessionStamp(userId: UUID, taskIds: List<UUID>) {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entities = backlogTaskRepository.findAllByBoardIdAndIdIn(boardId, taskIds)
        for (entity in entities) {
            entity.lastScheduledInSessionId = null
            entity.updatedAt = Instant.now()
        }
        backlogTaskRepository.saveAll(entities)
        taskChangeService.bumpWatermark(userId)
    }

    @Transactional
    fun unscheduleTask(userId: UUID, id: UUID) {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entity = backlogTaskRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Task $id not found")
        entity.lastScheduledInSessionId = null
        entity.updatedAt = Instant.now()
        backlogTaskRepository.save(entity)
        taskChangeService.bumpWatermark(userId)
    }

    @Transactional
    fun deleteTask(userId: UUID, id: UUID) {
        val boardId = boardMembershipService.resolveSoleBoard(userId)
        val entity = backlogTaskRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Task $id not found")
        val title = boardCrypto.decrypt(boardId, entity.title) ?: ""
        val status = entity.status!!
        backlogTaskRepository.delete(entity)
        taskChangeService.recordDeleted(userId, id, title, status)
        taskChangeService.bumpWatermark(userId)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Computes a sort key that goes after all existing tasks on the board. */
    private fun computeAppendKey(boardId: UUID): String {
        val maxKey = backlogTaskRepository.findMaxSortKeyByBoardId(boardId)
        return if (maxKey == null) SortKeyGenerator.INITIAL else SortKeyGenerator.after(maxKey)
    }

    /**
     * Reassigns evenly-spaced sort keys to all of the board's tasks.
     * Called lazily when any key exceeds [REBALANCE_KEY_LENGTH_THRESHOLD].
     */
    private fun rebalanceKeys(boardId: UUID) {
        val tasks = backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)
        val freshKeys = SortKeyGenerator.spreadKeys(tasks.size)
        tasks.zip(freshKeys).forEach { (task, key) -> task.sortKey = key }
        backlogTaskRepository.saveAll(tasks)
    }

    private fun resolveOrCreateTags(boardId: UUID, inputs: List<TagInput>): MutableSet<BacklogTaskTagEntity> {
        val existing = tagRepository.findAllByBoardId(boardId)

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
                this.boardId = boardId
                this.label = input.label.trim()
                this.colorId = TagColor.valueOf(input.colorId.uppercase())
            }.let { tagRepository.save(it) }
        }.toMutableSet()
    }
}
