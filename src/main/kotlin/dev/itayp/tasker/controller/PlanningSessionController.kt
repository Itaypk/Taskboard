package dev.itayp.tasker.controller

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.request.AddToPlanRequest
import dev.itayp.tasker.model.response.CurrentPlanResponse
import dev.itayp.tasker.model.response.OneOffEventResponse
import dev.itayp.tasker.model.response.PlanSummaryResponse
import dev.itayp.tasker.model.response.PlanTaskResponse
import dev.itayp.tasker.model.response.TimeSlotResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import dev.itayp.tasker.planning.PlanFinalizationService
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class PlanningSessionController(
    private val planningSessionService: PlanningSessionService,
    private val backlogTaskService: BacklogTaskService,
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
    private val planFinalizationService: PlanFinalizationService,
    private val oneOffEventService: OneOffEventService,
    private val userCrypto: UserCryptoService,
) {

    @GetMapping("/plans/current")
    fun getCurrentPlan(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<CurrentPlanResponse> {
        val session = planningSessionService.findCurrentPlan(principal.userId)
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(buildPlanResponse(principal.userId, session))
    }

    /**
     * The finalized plan for a specific week (the week's start date in ISO form, e.g. 2026-06-01), or
     * 204 if that week was never planned. Backs the drawer's prev/next week paging.
     */
    @GetMapping("/plans/week/{weekStart}")
    fun getPlanForWeek(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) weekStart: LocalDate,
    ): ResponseEntity<CurrentPlanResponse> {
        val session = planningSessionService.findPlanForWeek(principal.userId, weekStart)
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(buildPlanResponse(principal.userId, session))
    }

    /**
     * One-off calendar events whose start falls inside the user's local ISO week beginning at
     * [weekStart]. Independent of whether a plan exists for that week — events are surfaced as
     * soon as the user captures them. Read-only.
     */
    @GetMapping("/plans/week/{weekStart}/events")
    fun getEventsForWeek(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) weekStart: LocalDate,
    ): List<OneOffEventResponse> =
        oneOffEventService.listForLocalWeek(principal.userId, weekStart).map { event ->
            OneOffEventResponse(
                id = event.id.toString(),
                title = event.title,
                startsAt = event.startsAt.toString(),
                endsAt = event.endsAt.toString(),
                location = event.location,
                notes = event.notes,
            )
        }

    /**
     * Lightweight index of the user's finalized plans (no task bodies), most recent week first. Lets
     * the drawer know which weeks are navigable and render markers without fetching every plan.
     */
    @GetMapping("/plans")
    fun listPlans(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): List<PlanSummaryResponse> =
        planningSessionService.listFinalizedPlans(principal.userId).map { plan ->
            PlanSummaryResponse(
                id = plan.session.id.toString(),
                weekStart = plan.session.weekStart.toString(),
                weekEnd = plan.session.weekStart.plusDays(6).toString(),
                status = plan.session.status.name.lowercase(),
                taskCount = plan.taskCount,
                hasSummary = !plan.session.summary.isNullOrBlank(),
            )
        }

    private fun buildPlanResponse(userId: UUID, session: PlanningSession): CurrentPlanResponse {
        val sessionId = session.id

        val backlogTaskMap = backlogTaskService
            .getTasksScheduledInSession(userId, sessionId)
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
                notes = userCrypto.decrypt(userId, pt.notes),
            )
        }

        val weekStart = session.weekStart
        return CurrentPlanResponse(
            id = sessionId.toString(),
            status = session.status.name.lowercase(),
            startedAt = session.startedAt.toString(),
            endedAt = session.endedAt?.toString(),
            summary = session.summary,
            tasks = planTaskResponses,
            weekStart = weekStart.toString(),
            weekEnd = weekStart.plusDays(6).toString(),
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

        val task = backlogTaskService.findTask(principal.userId, taskId)
            ?: return ResponseEntity.notFound().build()

        planFinalizationService.addTaskToSession(
            principal.userId,
            session.id,
            AgreedPlanTask(taskId = taskId, title = task.title, slots = listOf(AgreedTimeSlot(request.startIso, request.endIso))),
        )

        return ResponseEntity.noContent().build()
    }
}
