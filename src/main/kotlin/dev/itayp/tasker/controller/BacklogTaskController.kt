package dev.itayp.tasker.controller

import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.model.response.TaskResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class BacklogTaskController(private val backlogTaskService: BacklogTaskService) {

    @GetMapping("/tasks")
    fun getBacklogTasks(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<List<TaskResponse>> =
        ResponseEntity.ok(backlogTaskService.getAllTasksForUser(principal.userId).map { it.toResponse() })

    @PostMapping("/tasks")
    fun createBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: CreateBacklogTaskRequest,
    ): ResponseEntity<TaskResponse> {
        logger.info("Creating backlog task: ${request.title}")
        val task = backlogTaskService.createTask(principal.userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(task.toResponse())
    }

    @PutMapping("/tasks/{id}")
    fun updateBacklogTask(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable id: UUID,
        @RequestBody request: UpdateBacklogTaskRequest,
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

    companion object {
        private val logger = LoggerFactory.getLogger(BacklogTaskController::class.java)
    }
}
