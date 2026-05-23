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
@RequestMapping("/api/v1")
class BacklogTaskController(
    private val backlogTaskService: BacklogTaskService,
    private val backlogTaskChangeService: BacklogTaskChangeService,
    private val clock: Clock,
) {

    @GetMapping("/tasks/has-changes")
    fun hasTaskChanges(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestParam since: String,
    ): ResponseEntity<HasChangesResponse> {
        val sinceInstant = runCatching { Instant.parse(since) }.getOrElse {
            return ResponseEntity.badRequest().build()
        }
        val checkedAt = clock.instant()
        val hasChanges = backlogTaskChangeService.hasChangesSince(principal.userId, sinceInstant)
        return ResponseEntity.ok(HasChangesResponse(hasChanges = hasChanges, checkedAt = checkedAt.toString()))
    }

    @GetMapping("/tasks")
    fun getBacklogTasks(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<List<TaskResponse>> {
        val statusFilter = parseStatusFilter(status)
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(
            backlogTaskService.getTasksForUser(principal.userId, statusFilter.value).map { it.toResponse() }
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

    @PostMapping("/tasks")
    fun createBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @Valid @RequestBody request: CreateBacklogTaskRequest,
    ): ResponseEntity<TaskResponse> {
        logger.info("Creating backlog task: ${request.title}")
        val task = backlogTaskService.createTask(principal.userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(task.toResponse())
    }

    @PutMapping("/tasks/{id}")
    fun updateBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateBacklogTaskRequest,
    ): ResponseEntity<TaskResponse> {
        return try {
            ResponseEntity.ok(backlogTaskService.updateTask(principal.userId, id, request).toResponse())
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/tasks/{id}")
    fun deleteBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        return try {
            backlogTaskService.deleteTask(principal.userId, id)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    @PatchMapping("/tasks/{id}/reorder")
    fun reorderBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
        @RequestBody request: ReorderTaskRequest,
    ): ResponseEntity<TaskResponse> {
        return try {
            ResponseEntity.ok(backlogTaskService.reorderTask(principal.userId, id, request).toResponse())
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BacklogTaskController::class.java)
    }
}
