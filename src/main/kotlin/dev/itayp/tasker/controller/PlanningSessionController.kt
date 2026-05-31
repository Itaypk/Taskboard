package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.request.AddToPlanRequest
import dev.itayp.tasker.model.response.CurrentPlanResponse
import dev.itayp.tasker.model.response.PlanTaskResponse
import dev.itayp.tasker.model.response.TimeSlotResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import dev.itayp.tasker.planning.PlanFinalizationService
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class PlanningSessionController(
    private val planningSessionService: PlanningSessionService,
    private val backlogTaskService: BacklogTaskService,
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
    private val planFinalizationService: PlanFinalizationService,
    private val userCrypto: UserCryptoService,
) {

    @GetMapping("/plans/current")
    fun getCurrentPlan(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<CurrentPlanResponse> {
        val session = planningSessionService.findCurrentPlan(principal.userId)
            ?: return ResponseEntity.noContent().build()
        val sessionId = session.id

        val backlogTaskMap = backlogTaskService
            .getTasksScheduledInSession(principal.userId, sessionId)
            .associate { it.id to it.toResponse() }

        val plannedTasks = plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)
        val slotsByPlannedTask = plannedTaskSlotRepository
            .findAllByPlannedTaskIdIn(plannedTasks.mapNotNull { it.id })
            .groupBy { it.plannedTaskId }

        val planTaskResponses = plannedTasks.mapNotNull { pt ->
            val backlogTask = pt.backlogTaskId?.let { backlogTaskMap[it] } ?: return@mapNotNull null
            PlanTaskResponse(
                task = backlogTask,
                slots = slotsByPlannedTask[pt.id].orEmpty().map { slot ->
                    TimeSlotResponse(startIso = slot.startIso!!, endIso = slot.endIso!!, label = slot.label)
                },
                notes = userCrypto.decrypt(principal.userId, pt.notes),
            )
        }

        val weekStart = session.weekStart
        val weekEnd = weekStart.plusDays(6)

        return ResponseEntity.ok(
            CurrentPlanResponse(
                id = sessionId.toString(),
                status = session.status.name.lowercase(),
                startedAt = session.startedAt.toString(),
                endedAt = session.endedAt?.toString(),
                summary = session.summary,
                tasks = planTaskResponses,
                weekStart = weekStart.toString(),
                weekEnd = weekEnd.toString(),
            )
        )
    }

    @PostMapping("/plans/current/tasks/{taskId}")
    fun addTaskToCurrentPlan(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: AddToPlanRequest,
    ): ResponseEntity<Void> {
        val session = planningSessionService.findCurrentPlan(principal.userId)
            ?: return ResponseEntity.unprocessableContent().build()

        val task = backlogTaskService.getTaskById(principal.userId, taskId)
            ?: return ResponseEntity.notFound().build()

        planFinalizationService.addTaskToSession(
            principal.userId,
            session.id,
            AgreedPlanTask(taskId = taskId, title = task.title, slots = listOf(AgreedTimeSlot(request.startIso, request.endIso))),
        )

        return ResponseEntity.noContent().build()
    }
}
