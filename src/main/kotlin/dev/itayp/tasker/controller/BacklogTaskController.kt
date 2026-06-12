package dev.itayp.tasker.controller

import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.ReorderTaskRequest
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.model.response.HasChangesResponse
import dev.itayp.tasker.model.response.TaskResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/boards/{boardId}/tasks")
class BacklogTaskController(
    private val backlogTaskService: BacklogTaskService,
    private val backlogTaskChangeService: BacklogTaskChangeService,
    private val boardMembershipService: BoardMembershipService,
    private val clock: Clock,
) {

    /**
     * Board-scoped staleness check: the watermark is keyed by board, so this reflects exactly this
     * board's changes — including edits made by other members — and nothing from the user's other
     * boards.
     */
    @GetMapping("/has-changes")
    fun hasTaskChanges(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @RequestParam since: String,
    ): ResponseEntity<HasChangesResponse> {
        boardMembershipService.requireMember(principal.userId, boardId)
        val sinceInstant = runCatching { Instant.parse(since) }.getOrElse {
            return ResponseEntity.badRequest().build()
        }
        val checkedAt = clock.instant()
        val hasChanges = backlogTaskChangeService.changedSince(boardId, sinceInstant)
        return ResponseEntity.ok(HasChangesResponse(hasChanges = hasChanges, checkedAt = checkedAt.toString()))
    }

    @GetMapping
    fun getBacklogTasks(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<List<TaskResponse>> {
        val statusFilter = parseStatusFilter(status)
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(
            backlogTaskService.getTasks(principal.userId, boardId, statusFilter.value).map { it.toResponse() }
        )
    }

    /**
     * `null` value with `null` wrapped means "all"; non-null wrapped value means filter to that status.
     * Outer `null` means the param was malformed.
     */
    private data class StatusFilter(val value: TaskStatus?)

    private fun parseStatusFilter(raw: String?): StatusFilter? = when (raw?.lowercase()) {
        null, "todo" -> StatusFilter(TaskStatus.TODO)
        "done" -> StatusFilter(TaskStatus.DONE)
        "archived" -> StatusFilter(TaskStatus.ARCHIVED)
        "all" -> StatusFilter(null)
        else -> null
    }

    @PostMapping
    fun createBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: CreateBacklogTaskRequest,
    ): ResponseEntity<TaskResponse> {
        logger.info("Creating backlog task on board $boardId")
        val task = backlogTaskService.createTask(principal.userId, boardId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(task.toResponse())
    }

    @PutMapping("/{id}")
    fun updateBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateBacklogTaskRequest,
    ): ResponseEntity<TaskResponse> {
        return try {
            ResponseEntity.ok(backlogTaskService.updateTask(principal.userId, boardId, id, request).toResponse())
        } catch (_: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        return try {
            backlogTaskService.deleteTask(principal.userId, boardId, id)
            ResponseEntity.noContent().build()
        } catch (_: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}/plan-schedule")
    fun unscheduleTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        return try {
            backlogTaskService.unscheduleTask(principal.userId, boardId, id)
            ResponseEntity.noContent().build()
        } catch (_: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @PatchMapping("/{id}/reorder")
    fun reorderBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @PathVariable id: UUID,
        @RequestBody request: ReorderTaskRequest,
    ): ResponseEntity<TaskResponse> {
        return try {
            ResponseEntity.ok(backlogTaskService.reorderTask(principal.userId, boardId, id, request).toResponse())
        } catch (_: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BacklogTaskController::class.java)
    }
}
