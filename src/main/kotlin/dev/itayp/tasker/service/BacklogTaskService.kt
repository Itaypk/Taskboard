package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskRecurrence
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.RecurrenceInput
import dev.itayp.tasker.model.request.ReorderTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.TaskUrl
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.model.request.toInput
import dev.itayp.tasker.model.request.toRule
import dev.itayp.tasker.model.request.validationError
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.planning.PlanWatermarkService
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.TaskCompletionCancellationService
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
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

/** Statuses meaning the task's originally-scheduled time block will never happen. */
private val TERMINAL_STATUSES = setOf(TaskStatus.DONE, TaskStatus.ARCHIVED)

@Service
class BacklogTaskService(
    private val backlogTaskRepository: BacklogTaskRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val tagRepository: BacklogTaskTagRepository,
    private val taskChangeService: BacklogTaskChangeService,
    private val planWatermarkService: PlanWatermarkService,
    private val userSettingsService: UserSettingsService,
    private val boardMembershipService: BoardMembershipService,
    private val boardCrypto: BoardCryptoService,
    private val userRepository: UserRepository,
    private val clock: Clock,
    private val taskCompletionCancellationService: TaskCompletionCancellationService,
    private val plannedTaskService: PlannedTaskService,
) {
    private val log = LoggerFactory.getLogger(BacklogTaskService::class.java)

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
     * Tasks across **every** board the user belongs to — the AI assistant's view (backlog search,
     * `find_task`, suggestion sampling). `status == null` means everything except archived. The
     * future-dated filter applies to the TODO view exactly as in [getTasks].
     *
     * Tasks the user has hidden from the assistant are dropped here: this method feeds only AI read
     * paths (the web board view uses [getTasks], which keeps them visible). If a future non-AI caller
     * is added, revisit this filter.
     */
    @Transactional(readOnly = true)
    fun getTasksAcrossBoards(userId: UUID, status: TaskStatus?): List<BacklogTask> {
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return emptyList()
        val entities = when (status) {
            null -> backlogTaskRepository.findAllByBoardIdInAndStatusNotOrderBySortKeyAsc(boardIds, TaskStatus.ARCHIVED)
            else -> backlogTaskRepository.findAllByBoardIdInAndStatusOrderBySortKeyAsc(boardIds, status)
        }
        val tasks = entities.map { it.toDomain(boardCrypto) }.filterNot { it.hiddenFromAssistant }
        return if (status == TaskStatus.TODO) filterOutFutureDated(userId, tasks) else tasks
    }

    /**
     * Open (`TODO`) tasks with a deadline on or before [onOrBefore], across every board the user
     * belongs to — the daily digest's "due" section. Not an AI read path, so tasks hidden from the
     * assistant are kept: hiding a task from the AI shouldn't hide its deadline from its owner.
     */
    @Transactional(readOnly = true)
    fun findDueTasksAcrossBoards(userId: UUID, onOrBefore: LocalDate): List<BacklogTask> {
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return emptyList()
        return backlogTaskRepository
            .findAllByBoardIdInAndStatusAndDeadlineLessThanEqual(boardIds, TaskStatus.TODO, onOrBefore)
            .map { it.toDomain(boardCrypto) }
    }

    /** The given tasks, limited to the boards the user belongs to; missing ids are simply absent. */
    @Transactional(readOnly = true)
    fun findTasksAcrossBoards(userId: UUID, ids: Collection<UUID>): List<BacklogTask> {
        if (ids.isEmpty()) return emptyList()
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.isEmpty()) return emptyList()
        return backlogTaskRepository.findAllByBoardIdInAndIdIn(boardIds, ids).map { it.toDomain(boardCrypto) }
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

    /**
     * Marks a task done on whichever of the user's boards it lives on — the user-scoped bridge behind
     * the "Mark done" reminder button (the channel reply layer doesn't know the board). Idempotent
     * (already-done is a no-op), and returns null when the task no longer exists so the caller can tell
     * the user gracefully.
     *
     * Routes the status change through [updateTask] (with a request rebuilt from the task's current
     * fields) rather than mutating the entity directly, so access checks and state-update side effects
     * stay in a single flow — the same path the UI's "Mark done" context-menu action takes.
     */
    @Transactional
    fun markDone(userId: UUID, id: UUID): BacklogTask? {
        val boardIds = boardMembershipService.listBoardIds(userId)
        val entity = if (boardIds.isEmpty()) null else backlogTaskRepository.findByIdAndBoardIdIn(id, boardIds)
        if (entity == null) {
            log.warn("markDone: task {} not found for user {}", id, userId)
            return null
        }
        val task = entity.toDomain(boardCrypto)
        // Idempotent — and avoids a needless re-encrypt/update — if the task is already done.
        if (task.status == TaskStatus.DONE) return task
        // A recurring task never reaches DONE, so the guard above can't catch a repeat. A second tap
        // on a reminder button (or a retried external call) must not save another copy and jump the
        // schedule again.
        if (task.recurrence != null && task.lastCompletedOn == todayFor(userId)) return task
        return updateTask(userId, task.boardId, id, rebuildRequest(task, TaskStatus.DONE))
    }

    /**
     * Archives a task on whichever of the user's boards it lives on — the user-scoped bridge behind
     * the planning-reconciliation "archive it" action (the channel reply layer doesn't know the
     * board). Symmetric to [markDone]: idempotent (already-archived is a no-op), returns null when the
     * task no longer exists, and routes through [updateTask] so access checks and change events fire
     * on the same path as the UI. Archive is the reversible delete — there is no hard delete.
     */
    @Transactional
    fun archive(userId: UUID, id: UUID): BacklogTask? {
        val boardIds = boardMembershipService.listBoardIds(userId)
        val entity = if (boardIds.isEmpty()) null else backlogTaskRepository.findByIdAndBoardIdIn(id, boardIds)
        if (entity == null) {
            log.warn("archive: task {} not found for user {}", id, userId)
            return null
        }
        val task = entity.toDomain(boardCrypto)
        if (task.status == TaskStatus.ARCHIVED) return task
        return updateTask(userId, task.boardId, id, rebuildRequest(task, TaskStatus.ARCHIVED))
    }

    /** A complete full-replace request carrying every current field, with only the status changed. */
    private fun rebuildRequest(task: BacklogTask, status: TaskStatus) = UpdateBacklogTaskRequest(
        title = task.title,
        description = task.description,
        url = task.url,
        priority = task.priority?.name?.lowercase(),
        deadline = task.deadline?.toString(),
        estimatedMinutes = task.estimatedMinutes,
        status = status.name.lowercase(),
        categoryId = task.category.id.toString(),
        tags = task.tags.map { TagInput(it.id.toString(), it.label, it.colorId.name.lowercase()) },
        relevantFrom = task.relevantFrom?.toString(),
        hiddenFromAssistant = task.hiddenFromAssistant,
        recurrence = task.recurrence?.toInput(),
    )

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
        val today = todayFor(userId)
        return tasks.filter { it.relevantFrom == null || !it.relevantFrom.isAfter(today) }
    }

    /** Today in the user's own timezone — `relevant_from` and recurrence both work on local days. */
    private fun todayFor(userId: UUID): LocalDate {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        return LocalDate.ofInstant(clock.instant(), zone)
    }

    @Transactional
    fun createTask(userId: UUID, boardId: UUID, request: CreateBacklogTaskRequest): BacklogTask {
        boardMembershipService.requireMember(userId, boardId)
        // Bean validation covers the web's own POST; this also catches callers that hand the service a
        // request directly (the external API builds its own DTOs).
        if (!TaskUrl.isAcceptable(request.url)) throw InvalidTaskUrlException()

        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndBoardId(categoryId, boardId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val sortKey = computeAppendKey(boardId)
        val rule = parseRecurrence(request.recurrence)
        val requestedStatus = TaskStatus.valueOf(request.status.uppercase())

        val entity = BacklogTaskEntity().apply {
            this.boardId = boardId
            this.title = boardCrypto.encrypt(boardId, request.title)
            this.description = boardCrypto.encrypt(boardId, request.description)
            this.url = request.url
            this.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
            this.estimatedMinutes = request.estimatedMinutes
            // A recurring task is never stored DONE; completing one means rolling it forward.
            this.status = if (rule != null && requestedStatus == TaskStatus.DONE) TaskStatus.TODO else requestedStatus
            this.category = category
            this.tags = resolveOrCreateTags(boardId, request.tags)
            this.sortKey = sortKey
            this.createdAt = Instant.now()
            this.updatedAt = null
            this.hiddenFromAssistant = request.hiddenFromAssistant
        }
        applySchedule(entity, userId, rule, request.relevantFrom, request.deadline)

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
        if (!TaskUrl.isAcceptable(request.url, entity.url)) throw InvalidTaskUrlException()

        val categoryId = UUID.fromString(request.categoryId)
        val category = categoryRepository.findByIdAndBoardId(categoryId, boardId)
            ?: throw NoSuchElementException("Category $categoryId not found")

        val previousStatus = entity.status!!
        val newStatus = TaskStatus.valueOf(request.status.uppercase())
        val rule = parseRecurrence(request.recurrence)
        val completesRecurring = rule != null && newStatus == TaskStatus.DONE && previousStatus != TaskStatus.DONE
        // A recurring task is never stored DONE, so `previousStatus` can't recognize a repeat: the row
        // is TODO again the moment it rolls. `markDone` guards the reminder and external paths the same
        // way; without this, a double tap (or a tab replaying a stale payload) would file a second copy
        // and push the schedule another period. Read-then-check, so two *simultaneous* completions can
        // still both get through — it closes the reachable, sequential case.
        val alreadyCompletedToday = completesRecurring && entity.lastCompletedOn == todayFor(userId)
        val rollsForward = completesRecurring && !alreadyCompletedToday
        if (alreadyCompletedToday) log.debug("Recurring task {} was already completed today; ignoring the repeat", id)

        entity.title = boardCrypto.encrypt(boardId, request.title)
        entity.description = boardCrypto.encrypt(boardId, request.description)
        entity.url = request.url
        entity.priority = request.priority?.let { TaskPriority.valueOf(it.uppercase()) }
        entity.estimatedMinutes = request.estimatedMinutes
        // Never DONE while it recurs — including on the repeat, which rolls nothing but must not
        // leave the row in a status the recurrence machinery doesn't expect.
        entity.status = if (completesRecurring) TaskStatus.TODO else newStatus
        entity.category = category
        entity.tags = resolveOrCreateTags(boardId, request.tags)
        entity.hiddenFromAssistant = request.hiddenFromAssistant
        entity.updatedAt = Instant.now()
        // A repeat completion is precisely the case where the caller's dates are stale — it hasn't seen
        // the roll its first request caused — so writing them back would drag the occurrence into the
        // past. Keep the stored schedule instead.
        applySchedule(
            entity, userId, rule,
            if (alreadyCompletedToday) entity.relevantFrom?.toString() else request.relevantFrom,
            if (alreadyCompletedToday) entity.deadline?.toString() else request.deadline,
        )

        val completedCopy = if (rollsForward) {
            val today = todayFor(userId)
            // The copy is taken after the request's edits and before the roll, so it records this
            // occurrence's own dates.
            val copy = saveCompletedCopy(entity, request.title, request.description, today)
            val next = RecurrenceCalculator.nextOccurrence(rule, entity.relevantFrom, today)
            entity.lastCompletedOn = today
            entity.relevantFrom = next
            entity.deadline = RecurrenceCalculator.deadlineFor(rule, next)
            entity.rescheduleCount = 0
            // lastScheduledInSessionId is deliberately kept: the plan view joins planned_task rows to
            // tasks through it, so clearing it would drop the task from the week it was planned in.
            // The future relevant_from already takes it off the Week pill and the planner slate.
            copy
        } else {
            null
        }

        val saved = backlogTaskRepository.save(entity)
        // recordStatusChange is a no-op when the status is unchanged; the watermark must still
        // bump so field edits (title/description/tags/…) surface to a polling board tab.
        taskChangeService.recordStatusChange(boardId, userId, saved.id!!, request.title, previousStatus, saved.status!!)
        // A completion is recorded once, on the copy, so the weekly diff and stats see one DONE per
        // occurrence. No CREATED event for the copy: it isn't new work.
        completedCopy?.let {
            taskChangeService.recordStatusChange(boardId, userId, it.id!!, request.title, TaskStatus.TODO, TaskStatus.DONE)
            log.info("Recurring task {} completed; saved copy {}, next occurrence {}", saved.id, it.id, saved.relevantFrom)
        }
        taskChangeService.bumpWatermark(boardId)
        // Completing or archiving a task ahead of its scheduled time block(s) — done or no longer
        // planned for that time either way — makes any still-pending reminder and calendar invite for
        // it stale. Cancel them, mirroring what a plan revision does for a dropped slot.
        if (newStatus in TERMINAL_STATUSES && previousStatus !in TERMINAL_STATUSES && !alreadyCompletedToday) {
            entity.lastScheduledInSessionId?.let { sessionId ->
                taskCompletionCancellationService.cancelUpcomingSlots(userId, sessionId, id)
            }
        }
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
        // Removing a task from the plan makes any still-pending reminder and calendar invite for its
        // upcoming slots stale, exactly like a plan revision that drops the slot. Cancel them *before*
        // deleting the planned rows (the cancellation reads the slots), then drop the planned task so
        // it no longer lingers in the session's slot diff.
        entity.lastScheduledInSessionId?.let { sessionId ->
            taskCompletionCancellationService.cancelUpcomingSlots(userId, sessionId, id)
            plannedTaskService.deleteTaskFromSession(sessionId, id)
        }
        entity.lastScheduledInSessionId = null
        entity.updatedAt = Instant.now()
        backlogTaskRepository.save(entity)
        taskChangeService.bumpWatermark(boardId)
        // Unscheduling drops the task from the current plan view, so the plan watermark moves too.
        planWatermarkService.bump(userId)
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
            // The rule is carried (a duplicated chore is still a chore); completion history isn't.
            this.recurrence = source.recurrence
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
     * label/color. The claim and plan stamp are cleared because they belong to the origin
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

    /**
     * Throws [InvalidRecurrenceException] for an unknown kind or fields that don't fit it. The
     * external API validates the same rule up front to answer with its own instructional detail.
     */
    private fun parseRecurrence(input: RecurrenceInput?): TaskRecurrence? {
        if (input == null) return null
        input.validationError()?.let { throw InvalidRecurrenceException(it) }
        return input.toRule()
    }

    /**
     * Writes the rule and the task's dates. Without a rule the requested dates are stored as sent.
     * With one, `relevant_from` is the next occurrence (defaulting to the rule's first one) and the
     * deadline is always derived from it, ignoring any deadline the client sent — so the stored
     * absolute deadline that sorting, urgency and prompts rely on can never drift from the rule.
     */
    private fun applySchedule(
        entity: BacklogTaskEntity,
        userId: UUID,
        rule: TaskRecurrence?,
        requestedRelevantFrom: String?,
        requestedDeadline: String?,
    ) {
        entity.recurrence = rule
        val relevantFrom = requestedRelevantFrom?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        if (rule == null) {
            entity.relevantFrom = relevantFrom
            entity.deadline = requestedDeadline?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        } else {
            val occurrence = relevantFrom ?: RecurrenceCalculator.firstOccurrence(rule, todayFor(userId))
            entity.relevantFrom = occurrence
            entity.deadline = RecurrenceCalculator.deadlineFor(rule, occurrence)
        }
    }

    /** Saves the DONE record of one occurrence of [original]. See `docs/RECURRING-TASKS.md`. */
    private fun saveCompletedCopy(
        original: BacklogTaskEntity,
        title: String,
        description: String?,
        completedOn: LocalDate,
    ): BacklogTaskEntity {
        val boardId = original.boardId!!
        return backlogTaskRepository.save(BacklogTaskEntity().apply {
            this.boardId = boardId
            this.assigneeUserId = original.assigneeUserId
            this.title = boardCrypto.encrypt(boardId, title)
            this.description = boardCrypto.encrypt(boardId, description)
            this.url = original.url
            this.priority = original.priority
            this.deadline = original.deadline
            this.estimatedMinutes = original.estimatedMinutes
            this.status = TaskStatus.DONE
            this.category = original.category
            this.tags = original.tags.toMutableSet()
            this.sortKey = computeAppendKey(boardId)
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
            this.relevantFrom = original.relevantFrom
            this.hiddenFromAssistant = original.hiddenFromAssistant
            this.lastCompletedOn = completedOn
            this.recurrenceSourceId = original.id
        })
    }

    /**
     * Computes a sort key that goes after all existing tasks on the board.
     *
     * [SortKeyGenerator.after] grows the key by one character per call, so a board that is only ever
     * appended to (create, copy, move, recurrence roll-forward) would overflow the 255-char column after
     * ~250 tasks. Reordering is not the only place that rebalances: an over-long tail key does it here.
     */
    private fun computeAppendKey(boardId: UUID): String {
        val maxKey = backlogTaskRepository.findMaxSortKeyByBoardId(boardId) ?: return SortKeyGenerator.INITIAL
        val tailKey = if (maxKey.length > REBALANCE_KEY_LENGTH_THRESHOLD) rebalanceKeys(boardId) ?: maxKey else maxKey
        return SortKeyGenerator.after(tailKey)
    }

    /**
     * Reassigns evenly-spaced sort keys to all of the board's tasks and returns the last one (null for
     * an empty board). Called lazily when any key exceeds [REBALANCE_KEY_LENGTH_THRESHOLD].
     */
    private fun rebalanceKeys(boardId: UUID): String? {
        val tasks = backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)
        val freshKeys = SortKeyGenerator.spreadKeys(tasks.size)
        tasks.zip(freshKeys).forEach { (task, key) -> task.sortKey = key }
        backlogTaskRepository.saveAll(tasks)
        return freshKeys.lastOrNull()
    }

    private fun resolveOrCreateTags(boardId: UUID, inputs: List<TagInput>): MutableSet<BacklogTaskTagEntity> {
        val existing = tagRepository.findAllByBoardId(boardId)
        var createdAny = false

        val resolved = inputs.map { input ->
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
            createdAny = true
            BacklogTaskTagEntity().apply {
                this.boardId = boardId
                this.label = input.label.trim()
                this.colorId = TagColor.valueOf(input.colorId.uppercase())
            }.let { tagRepository.save(it) }
        }.toMutableSet()

        // Tags are only ever born here (as a side-effect of a task save); bump so open tabs refetch them.
        if (createdAny) taskChangeService.bumpTags(boardId)
        return resolved
    }
}

/** Raised when an assignee target is not a member of the board. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class AssigneeNotMemberException(userId: UUID, boardId: UUID) :
    RuntimeException("User $userId is not a member of board $boardId and cannot be assigned")

/** Raised for a malformed recurrence rule. Maps to HTTP 400 with code `INVALID_RECURRENCE` in `ApiExceptionHandler`. */
class InvalidRecurrenceException(message: String) : RuntimeException(message)

/**
 * Raised for a task link that isn't an `http(s)` URL and isn't the stored value carried back
 * unchanged (see [dev.itayp.tasker.model.request.TaskUrl]). Maps to HTTP 400 in
 * `ApiExceptionHandler`, which attributes it to the `url` field so the editor can show it inline.
 */
class InvalidTaskUrlException : RuntimeException(TaskUrl.REQUIREMENT_MESSAGE)

/** Raised when a move targets the board the task already lives on. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class SameBoardMoveException : RuntimeException("Task is already on that board")
