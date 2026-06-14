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
import dev.itayp.tasker.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
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
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getTasks(userId: UUID, boardId: UUID, status: TaskStatus?): List<BacklogTask> {
        boardMembershipService.requireMember(userId, boardId)
        val entities = when (status) {
            null -> backlogTaskRepository.findAllByBoardIdAndStatusNotOrderBySortKeyAsc(boardId, TaskStatus.ARCHIVED)
            else -> backlogTaskRepository.findAllByBoardIdAndStatusOrderBySortKeyAsc(boardId, status)
        }
        val tasks = entities.map { it.toDomain(boardCrypto) }
        // Hide future-dated tasks from the To Do view; all other statuses show them regardless.
        return if (status == TaskStatus.TODO) filterOutFutureDated(userId, tasks) else tasks
    }

    /**
     * Tasks across **every** board the user belongs to — the planner's view (`find_task`,
     * suggestion sampling). `status == null` means everything except archived. The future-dated
     * filter applies to the TODO view exactly as in [getTasks].
     */
    @Transactional(readOnly = true)
    fun getTasksAcrossBoards(userId: UUID, status: TaskStatus?): List<BacklogTask> {
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return emptyList()
        val entities = when (status) {
            null -> backlogTaskRepository.findAllByBoardIdInAndStatusNotOrderBySortKeyAsc(boardIds, TaskStatus.ARCHIVED)
            else -> backlogTaskRepository.findAllByBoardIdInAndStatusOrderBySortKeyAsc(boardIds, status)
        }
        val tasks = entities.map { it.toDomain(boardCrypto) }
        return if (status == TaskStatus.TODO) filterOutFutureDated(userId, tasks) else tasks
    }

    @Transactional(readOnly = true)
    fun getTaskById(userId: UUID, boardId: UUID, id: UUID): BacklogTask? {
        boardMembershipService.requireMember(userId, boardId)
        return backlogTaskRepository.findByIdAndBoardId(id, boardId)?.toDomain(boardCrypto)
    }

    /**
     * Finds a task by id on **any** board the user belongs to (the carried [BacklogTask.boardId]
     * tells the caller which one). Used by planner/web flows that reference a task by id without
     * knowing its board (`update_task`, "add to plan").
     */
    @Transactional(readOnly = true)
    fun findTask(userId: UUID, id: UUID): BacklogTask? {
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return null
        return backlogTaskRepository.findByIdAndBoardIdIn(id, boardIds)?.toDomain(boardCrypto)
    }

    @Transactional(readOnly = true)
    fun getTasksScheduledInSession(userId: UUID, sessionId: UUID): List<BacklogTask> {
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return emptyList()
        return backlogTaskRepository
            .findAllByBoardIdInAndLastScheduledInSessionIdOrderBySortKeyAsc(boardIds, sessionId)
            .map { it.toDomain(boardCrypto) }
    }

    @Transactional
    fun stampPlanningSession(userId: UUID, taskIds: List<UUID>, sessionId: UUID) {
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty() || taskIds.isEmpty()) return
        val entities = backlogTaskRepository.findAllByBoardIdInAndIdIn(boardIds, taskIds)
        for (entity in entities) {
            entity.lastScheduledInSessionId = sessionId
            // Decision 8: scheduling a shared task into your week claims it, but only if unclaimed —
            // a carry-over re-stamp never steals an existing claim.
            if (entity.assigneeUserId == null) entity.assigneeUserId = userId
        }
        backlogTaskRepository.saveAll(entities)
        entities.mapNotNull { it.boardId }.distinct().forEach { taskChangeService.bumpWatermark(it) }
    }

    /**
     * Sets or clears a task's assignee (Decision 7 — open coordination, not an ACL: any member may
     * assign any member or unassign). Bumps the board watermark so the claim propagates to other
     * members' open tabs, but records **no** change event — claim churn would drown the planner's
     * weekly diff (Decision 8 / PR 3).
     */
    @Transactional
    fun setAssignee(userId: UUID, boardId: UUID, taskId: UUID, assigneeUserId: UUID?): BacklogTask {
        boardMembershipService.requireMember(userId, boardId)
        val entity = backlogTaskRepository.findByIdAndBoardId(taskId, boardId)
            ?: throw NoSuchElementException("Task $taskId not found")
        if (assigneeUserId != null && !boardMembershipService.isMember(assigneeUserId, boardId)) {
            throw AssigneeNotMemberException(assigneeUserId, boardId)
        }
        entity.assigneeUserId = assigneeUserId
        val saved = backlogTaskRepository.save(entity)
        taskChangeService.bumpWatermark(boardId)
        return saved.toDomain(boardCrypto)
    }

    private fun filterOutFutureDated(userId: UUID, tasks: List<BacklogTask>): List<BacklogTask> {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.ofInstant(clock.instant(), zone)
        return tasks.filter { it.relevantFrom == null || !it.relevantFrom.isAfter(today) }
    }

    @Transactional
    fun createTask(userId: UUID, boardId: UUID, request: CreateBacklogTaskRequest): BacklogTask {
        boardMembershipService.requireMember(userId, boardId)
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
        taskChangeService.recordCreated(boardId, userId, saved.id!!, request.title, saved.status!!)
        taskChangeService.bumpWatermark(boardId)
        // Creating a real (non-tutorial) task is the genuine-engagement signal — stamp it once. This
        // is the API create path, which never produces tutorial tasks (those are seeded directly).
        userRepository.stampEngagedAt(userId, Instant.now())
        return saved.toDomain(boardCrypto)
    }

    @Transactional
    fun updateTask(userId: UUID, boardId: UUID, id: UUID, request: UpdateBacklogTaskRequest): BacklogTask {
        boardMembershipService.requireMember(userId, boardId)
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
        taskChangeService.recordStatusChange(boardId, userId, saved.id!!, request.title, previousStatus, newStatus)
        taskChangeService.bumpWatermark(boardId)
        return saved.toDomain(boardCrypto)
    }

    @Transactional
    fun reorderTask(userId: UUID, boardId: UUID, taskId: UUID, request: ReorderTaskRequest): BacklogTask {
        boardMembershipService.requireMember(userId, boardId)
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
        taskChangeService.bumpWatermark(boardId)

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
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty() || taskIds.isEmpty()) return
        val entities = backlogTaskRepository.findAllByBoardIdInAndIdIn(boardIds, taskIds)
        for (entity in entities) {
            entity.lastScheduledInSessionId = null
            entity.updatedAt = Instant.now()
        }
        backlogTaskRepository.saveAll(entities)
        entities.mapNotNull { it.boardId }.distinct().forEach { taskChangeService.bumpWatermark(it) }
    }

    @Transactional
    fun unscheduleTask(userId: UUID, boardId: UUID, id: UUID) {
        boardMembershipService.requireMember(userId, boardId)
        val entity = backlogTaskRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Task $id not found")
        entity.lastScheduledInSessionId = null
        entity.updatedAt = Instant.now()
        backlogTaskRepository.save(entity)
        taskChangeService.bumpWatermark(boardId)
    }

    /**
     * Deletes the seeded tutorial backlog in one go (the "clear tutorial" affordance). Tutorial tasks
     * are excluded from the planner and carry no real history, so we just drop them and bump the
     * watermark — no per-task change events.
     */
    @Transactional
    fun clearTutorialTasks(userId: UUID, boardId: UUID) {
        boardMembershipService.requireMember(userId, boardId)
        val tutorialTasks = backlogTaskRepository.findAllByBoardIdAndTutorialTrue(boardId)
        if (tutorialTasks.isEmpty()) return
        backlogTaskRepository.deleteAll(tutorialTasks)
        taskChangeService.bumpWatermark(boardId)
    }

    @Transactional
    fun deleteTask(userId: UUID, boardId: UUID, id: UUID) {
        boardMembershipService.requireMember(userId, boardId)
        val entity = backlogTaskRepository.findByIdAndBoardId(id, boardId)
            ?: throw NoSuchElementException("Task $id not found")
        val title = boardCrypto.decrypt(boardId, entity.title) ?: ""
        val status = entity.status!!
        backlogTaskRepository.delete(entity)
        taskChangeService.recordDeleted(boardId, userId, id, title, status)
        taskChangeService.bumpWatermark(boardId)
    }

    /**
     * Creates a copy of a task on the **same** board: a fresh id and sort key (appended last), a
     * " (copy)" title suffix, and `status = TODO` so the duplicate is immediately actionable. The
     * claim and any plan stamp are deliberately not carried over. Category and tags are shared with
     * the original (same board), so no re-resolution is needed.
     */
    @Transactional
    fun duplicateTask(userId: UUID, boardId: UUID, taskId: UUID): BacklogTask {
        boardMembershipService.requireMember(userId, boardId)
        val source = backlogTaskRepository.findByIdAndBoardId(taskId, boardId)
            ?: throw NoSuchElementException("Task $taskId not found")

        val copyTitle = (boardCrypto.decrypt(boardId, source.title) ?: "") + " (copy)"

        val entity = BacklogTaskEntity().apply {
            this.boardId = boardId
            this.title = boardCrypto.encrypt(boardId, copyTitle)
            this.description = boardCrypto.encrypt(boardId, boardCrypto.decrypt(boardId, source.description))
            this.url = source.url
            this.priority = source.priority
            this.deadline = source.deadline
            this.estimatedMinutes = source.estimatedMinutes
            this.status = TaskStatus.TODO
            this.category = source.category
            this.tags = source.tags.toMutableSet()
            this.sortKey = computeAppendKey(boardId)
            this.createdAt = Instant.now()
            this.updatedAt = null
            this.relevantFrom = source.relevantFrom
        }

        val saved = backlogTaskRepository.save(entity)
        taskChangeService.recordCreated(boardId, userId, saved.id!!, copyTitle, saved.status!!)
        taskChangeService.bumpWatermark(boardId)
        userRepository.stampEngagedAt(userId, Instant.now())
        return saved.toDomain(boardCrypto)
    }

    /**
     * Moves a task to another board the user belongs to, into the caller-chosen [targetCategoryId]
     * (categories are board-scoped, so the destination category is picked explicitly in the UI). The
     * task keeps its id (and thus its change history); only its board context is rewritten. Board-owned
     * content is re-encrypted under the destination board's DEK and tags are re-resolved by
     * label/colour. The claim and plan stamp are cleared because they belong to the origin
     * board/session. No CREATED/DELETED change events are recorded — the task is the same one, so
     * double-recording would inflate stats and the planner's weekly diff; both boards' watermarks are
     * bumped so open tabs refresh.
     */
    @Transactional
    fun moveTask(userId: UUID, boardId: UUID, taskId: UUID, targetBoardId: UUID, targetCategoryId: UUID): BacklogTask {
        if (boardId == targetBoardId) throw SameBoardMoveException()
        boardMembershipService.requireMember(userId, boardId)
        boardMembershipService.requireMember(userId, targetBoardId)

        val entity = backlogTaskRepository.findByIdAndBoardId(taskId, boardId)
            ?: throw NoSuchElementException("Task $taskId not found")
        val targetCategory = categoryRepository.findByIdAndBoardId(targetCategoryId, targetBoardId)
            ?: throw NoSuchElementException("Category $targetCategoryId not found on board $targetBoardId")

        val title = boardCrypto.decrypt(boardId, entity.title) ?: ""
        val description = boardCrypto.decrypt(boardId, entity.description)
        val tagInputs = entity.tags.map { TagInput(label = it.label!!, colorId = it.colorId!!.name) }

        entity.boardId = targetBoardId
        entity.title = boardCrypto.encrypt(targetBoardId, title)
        entity.description = boardCrypto.encrypt(targetBoardId, description)
        entity.category = targetCategory
        entity.tags = resolveOrCreateTags(targetBoardId, tagInputs)
        entity.assigneeUserId = null
        entity.lastScheduledInSessionId = null
        entity.sortKey = computeAppendKey(targetBoardId)
        entity.updatedAt = Instant.now()

        val saved = backlogTaskRepository.save(entity)
        taskChangeService.bumpWatermark(boardId)
        taskChangeService.bumpWatermark(targetBoardId)
        return saved.toDomain(boardCrypto)
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

/** Raised when an assignee target is not a member of the board. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class AssigneeNotMemberException(userId: UUID, boardId: UUID) :
    RuntimeException("User $userId is not a member of board $boardId and cannot be assigned")

/** Raised when a move targets the board the task already lives on. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class SameBoardMoveException : RuntimeException("Task is already on that board")
