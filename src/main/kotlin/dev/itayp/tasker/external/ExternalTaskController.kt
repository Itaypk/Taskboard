package dev.itayp.tasker.external

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.RecurrenceInput
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.model.request.toInput
import dev.itayp.tasker.model.request.TaskUrl
import dev.itayp.tasker.model.request.validationError
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.BoardService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The external task API. Every method is a thin adapter: argument validation and DTO shaping
 * here, all authorization and business rules in the existing services (each of which opens with
 * `BoardMembershipService.requireMember`, so a token cannot reach a board its owner isn't on).
 *
 * Differences from `/api/v1/boards/{boardId}/tasks`, all deliberate:
 * - board id is optional everywhere (defaults to the user's default board / spans all boards on read)
 * - `PATCH` is a genuine partial update; the SPA's `PUT` is a full replace
 * - `complete` and `archive` are single-purpose actions rather than status writes
 * - free-text search via `q`
 * - no hard delete — archive is the reversible equivalent and the right default for a script
 */
@RestController
@RequestMapping("/api/external/v1/tasks")
class ExternalTaskController(
    private val backlogTaskService: BacklogTaskService,
    private val boardMembershipService: BoardMembershipService,
    private val boardService: BoardService,
    private val categoryService: BacklogTaskCategoryService,
) {

    @GetMapping
    fun listTasks(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestParam(required = false) board: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) priority: String?,
        @RequestParam(required = false, defaultValue = "false") includeHidden: Boolean,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
    ): ResponseEntity<*> {
        val userId = principal.userId

        val statusFilter = (
            parseStatusFilter(status)
                ?: return badRequest("Unknown status '$status'. Allowed: todo, done, archived, all.")
            ).value
        if (priority != null && priority.lowercase() !in TaskPriority.allowedValues) {
            return badRequest("Unknown priority '$priority'. Allowed: ${TaskPriority.allowedValues.joinToString()}.")
        }
        val effectiveLimit = limit.coerceIn(1, MAX_LIMIT)
        val effectiveOffset = offset.coerceAtLeast(0)

        val boardId = board?.let {
            it.toUuidOrNull() ?: return badRequest("'board' is not a valid id.")
        }

        // Cross-board is the default: a caller asking "what's on my plate" shouldn't have to
        // enumerate boards first. getTasksAcrossBoards additionally drops assistant-hidden tasks,
        // which is exactly the semantics we want here — includeHidden opts back in via the
        // board-scoped path.
        var tasks: List<BacklogTask> = when {
            boardId != null -> backlogTaskService.getTasks(userId, boardId, statusFilter)
            includeHidden -> boardMembershipService.listBoardIds(userId)
                .flatMap { backlogTaskService.getTasks(userId, it, statusFilter) }
            else -> backlogTaskService.getTasksAcrossBoards(userId, statusFilter)
        }
        if (boardId != null && !includeHidden) {
            tasks = tasks.filterNot { it.hiddenFromAssistant }
        }

        if (!q.isNullOrBlank()) {
            // Titles and descriptions are encrypted at rest, so this cannot be pushed into SQL —
            // the same reason the planner's backlog search filters in memory. Bounded by the
            // caller's task count, which is small at current scale.
            val needle = q.trim().lowercase()
            tasks = tasks.filter { task ->
                task.title.lowercase().contains(needle) ||
                    task.description?.lowercase()?.contains(needle) == true
            }
        }
        if (!tag.isNullOrBlank()) {
            val wanted = tag.trim().lowercase()
            tasks = tasks.filter { task -> task.tags.any { it.label.lowercase() == wanted } }
        }
        if (priority != null) {
            val wanted = TaskPriority.valueOf(priority.uppercase())
            tasks = tasks.filter { it.priority == wanted }
        }

        val total = tasks.size
        val page = tasks.drop(effectiveOffset).take(effectiveLimit)
        val names = boardNames(userId)
        return ResponseEntity.ok(
            ExternalTaskListResponse(
                tasks = page.map { it.toExternalResponse(names[it.boardId] ?: "") },
                count = page.size,
                truncated = effectiveOffset + page.size < total,
            )
        )
    }

    @GetMapping("/{id}")
    fun getTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val task = backlogTaskService.findTask(principal.userId, id)
            ?: return taskNotFound(id)
        return ResponseEntity.ok(task.toExternalResponse(boardNames(principal.userId)[task.boardId] ?: ""))
    }

    @PostMapping
    fun createTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: ExternalCreateTaskRequest,
    ): ResponseEntity<*> {
        val userId = principal.userId
        val title = request.title?.trim()
        if (title.isNullOrEmpty()) return badRequest("'title' is required and must not be blank.")

        val boardId = if (request.boardId != null) {
            request.boardId.toUuidOrNull() ?: return badRequest("'boardId' is not a valid id.")
        } else {
            // resolveDefaultBoard throws when the user belongs to no board at all.
            runCatching { boardMembershipService.resolveDefaultBoard(userId) }.getOrNull()
                ?: return unprocessable("You have no boards yet. Create one in the app first.")
        }

        // requireMember runs inside the category service, so an unauthorized board id is rejected
        // here before we ever reach createTask.
        val categories = categoryService.getCategories(userId, boardId)
        val categoryId = if (request.categoryId != null) {
            val parsed = request.categoryId.toUuidOrNull()
                ?: return badRequest("'categoryId' is not a valid id.")
            if (categories.none { category -> category.id == parsed }) {
                return unprocessable("Category $parsed does not exist on board $boardId.")
            }
            parsed
        } else {
            // Default to the board's first category so a bare {"title": "..."} call works.
            categories.firstOrNull()?.id
                ?: return unprocessable("Board $boardId has no categories. Create one in the app first.")
        }

        request.priority?.let {
            if (it.lowercase() !in TaskPriority.allowedValues) {
                return badRequest("Unknown priority '$it'. Allowed: ${TaskPriority.allowedValues.joinToString()}.")
            }
        }
        validateDate(request.deadline, "deadline")?.let { return it }
        validateDate(request.relevantFrom, "relevantFrom")?.let { return it }
        validateRecurrence(request.recurrence)?.let { return it }
        validateUrl(request.url)?.let { return it }

        val created = backlogTaskService.createTask(
            userId, boardId,
            CreateBacklogTaskRequest(
                title = title,
                description = request.description,
                url = request.url,
                priority = request.priority?.lowercase(),
                deadline = request.deadline,
                estimatedMinutes = request.estimatedMinutes,
                status = TaskStatus.TODO.name.lowercase(),
                categoryId = categoryId.toString(),
                tags = toTagInputs(request.tags),
                relevantFrom = request.relevantFrom,
                hiddenFromAssistant = request.hiddenFromAssistant ?: false,
                recurrence = request.recurrence,
            ),
        )
        logger.info("External API created task {} on board {}", created.id, boardId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(created.toExternalResponse(boardNames(userId)[created.boardId] ?: ""))
    }

    /**
     * Partial update. Reads the task, overlays only what the request actually carries, and hands a
     * complete request to `updateTask` — the same read-merge-write shape
     * `BacklogTaskService.markDone` already uses internally, so the full-replace service contract
     * stays untouched.
     */
    @PatchMapping("/{id}")
    fun updateTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: ExternalUpdateTaskRequest,
    ): ResponseEntity<*> {
        val userId = principal.userId
        val current = backlogTaskService.findTask(userId, id) ?: return taskNotFound(id)

        val clear = request.clear.orEmpty().map { it.trim() }.toSet()
        val unknownClear = clear - ExternalUpdateTaskRequest.CLEARABLE_FIELDS
        if (unknownClear.isNotEmpty()) {
            return badRequest(
                "Cannot clear ${unknownClear.joinToString()}. " +
                    "Clearable fields: ${ExternalUpdateTaskRequest.CLEARABLE_FIELDS.joinToString()}.",
            )
        }

        request.priority?.let {
            if (it.lowercase() !in TaskPriority.allowedValues) {
                return badRequest("Unknown priority '$it'. Allowed: ${TaskPriority.allowedValues.joinToString()}.")
            }
        }
        val newStatus = request.status?.let {
            parseTaskStatus(it) ?: return badRequest(
                "Unknown status '$it'. Allowed: ${TaskStatus.entries.joinToString { s -> s.name.lowercase() }}.",
            )
        } ?: current.status
        validateDate(request.deadline, "deadline")?.let { return it }
        validateDate(request.relevantFrom, "relevantFrom")?.let { return it }
        validateRecurrence(request.recurrence)?.let { return it }
        request.url?.let { validateUrl(it, current.url)?.let { problem -> return problem } }

        val categoryId = if (request.categoryId != null) {
            val parsed = request.categoryId.toUuidOrNull()
                ?: return badRequest("'categoryId' is not a valid id.")
            if (categoryService.getCategories(userId, current.boardId).none { c -> c.id == parsed }) {
                return unprocessable("Category $parsed does not exist on board ${current.boardId}.")
            }
            parsed
        } else {
            current.category.id
        }

        val merged = UpdateBacklogTaskRequest(
            title = request.title?.trim() ?: current.title,
            description = pick("description", clear, request.description, current.description),
            url = pick("url", clear, request.url, current.url),
            priority = pick("priority", clear, request.priority?.lowercase(), current.priority?.name?.lowercase()),
            deadline = pick("deadline", clear, request.deadline, current.deadline?.toString()),
            estimatedMinutes = pick("estimatedMinutes", clear, request.estimatedMinutes, current.estimatedMinutes),
            status = newStatus.name.lowercase(),
            categoryId = categoryId.toString(),
            tags = when {
                "tags" in clear -> emptyList()
                request.tags != null -> toTagInputs(request.tags)
                else -> current.tags.map { TagInput(it.id.toString(), it.label, it.colorId.name.lowercase()) }
            },
            relevantFrom = pick("relevantFrom", clear, request.relevantFrom, current.relevantFrom?.toString()),
            hiddenFromAssistant = request.hiddenFromAssistant ?: current.hiddenFromAssistant,
            recurrence = when {
                "recurrence" in clear -> null
                request.recurrence != null -> request.recurrence
                else -> current.recurrence?.toInput()
            },
        )

        val updated = backlogTaskService.updateTask(userId, current.boardId, id, merged)
        logger.info("External API updated task {}", id)
        return ResponseEntity.ok(updated.toExternalResponse(boardNames(userId)[updated.boardId] ?: ""))
    }

    /** Idempotent: completing an already-done task succeeds and returns it unchanged. */
    @PostMapping("/{id}/complete")
    fun completeTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val task = backlogTaskService.markDone(principal.userId, id)
            ?: return taskNotFound(id)
        logger.info("External API completed task {}", id)
        return ResponseEntity.ok(task.toExternalResponse(boardNames(principal.userId)[task.boardId] ?: ""))
    }

    /** The reversible delete. There is no hard-delete on this API by design. */
    @PostMapping("/{id}/archive")
    fun archiveTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val task = backlogTaskService.archive(principal.userId, id)
            ?: return taskNotFound(id)
        logger.info("External API archived task {}", id)
        return ResponseEntity.ok(task.toExternalResponse(boardNames(principal.userId)[task.boardId] ?: ""))
    }

    private fun boardNames(userId: UUID): Map<UUID, String> =
        boardService.listBoardsForUser(userId).associate { it.id to it.name }

    /** null → unchanged, listed in `clear` → unset, otherwise → the new value. */
    private fun <T> pick(field: String, clear: Set<String>, incoming: T?, current: T?): T? =
        when {
            field in clear -> null
            incoming != null -> incoming
            else -> current
        }

    private fun toTagInputs(labels: List<String>?): List<TagInput> =
        labels.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .map { TagInput(id = null, label = it, colorId = defaultColorFor(it)) }

    /**
     * color for a tag the caller invented. Derived from the label so the same tag name always
     * gets the same color, and so a batch of new tags doesn't come out monochrome. Only used
     * when no tag with that label already exists — `resolveOrCreateTags` matches by label first.
     */
    private fun defaultColorFor(label: String): String {
        val colors = TagColor.entries
        val index = Math.floorMod(label.lowercase().hashCode(), colors.size)
        return colors[index].name.lowercase()
    }

    /**
     * A null result means the value was malformed; a wrapped null means "all statuses except
     * archived". Mirrors the wrapper `BacklogTaskController` uses for the same three-way outcome.
     */
    private data class StatusFilter(val value: TaskStatus?)

    private fun parseStatusFilter(raw: String?): StatusFilter? = when (raw?.lowercase()) {
        null, "todo" -> StatusFilter(TaskStatus.TODO)
        "done" -> StatusFilter(TaskStatus.DONE)
        "archived" -> StatusFilter(TaskStatus.ARCHIVED)
        "all" -> StatusFilter(null)
        else -> null
    }

    private fun parseTaskStatus(raw: String): TaskStatus? =
        TaskStatus.entries.find { it.name.equals(raw, ignoreCase = true) }

    private fun validateDate(raw: String?, field: String): ResponseEntity<ProblemDetail>? {
        if (raw.isNullOrBlank()) return null
        return if (DATE_PATTERN.matches(raw)) null else badRequest("'$field' must be YYYY-MM-DD.")
    }

    /**
     * Checked here, like [validateRecurrence], so an agent gets an instructional detail rather than
     * the terse message `BacklogTaskService` raises for the SPA. [current] is the stored link: a
     * PATCH may carry an in-app tutorial link back unchanged, which the service accepts.
     */
    private fun validateUrl(url: String?, current: String? = null): ResponseEntity<ProblemDetail>? =
        if (TaskUrl.isAcceptable(url, current)) {
            null
        } else {
            badRequest("'url' must start with http:// or https://, or be omitted.")
        }

    private fun validateRecurrence(input: RecurrenceInput?): ResponseEntity<ProblemDetail>? =
        input?.validationError()?.let {
            badRequest(
                "Invalid 'recurrence': $it Shapes: {kind: EVERY_N_DAYS|EVERY_N_MONTHS, every}, " +
                    "{kind: WEEKLY, day: 1-7}, {kind: MONTHLY, day: 1-31}, {kind: YEARLY, month, day}; " +
                    "optional dueWithinDays 0-365.",
            )
        }

    private fun badRequest(detail: String): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, detail)

    /**
     * A 404 with a body. `findTask` spans every board the caller belongs to, so "not found" and
     * "not yours" are the same answer here — the detail says so rather than leaving a caller to
     * retry the same id against each board.
     */
    private fun taskNotFound(id: UUID): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "No task $id on any board you are a member of.")

    private fun unprocessable(detail: String): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, detail)

    /**
     * Errors are RFC 7807, matching the rest of the API. The `detail` is written for a model to
     * read: it names what was wrong and what the allowed values are, so an AI caller can correct
     * itself without a human in the loop.
     */
    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalTaskController::class.java)
        private const val MAX_LIMIT = 200
        private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")
    }
}
