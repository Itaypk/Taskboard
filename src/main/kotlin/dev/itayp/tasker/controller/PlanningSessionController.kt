package dev.itayp.tasker.controller

import dev.itayp.tasker.model.response.CurrentPlanResponse
import dev.itayp.tasker.model.response.toResponse
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class PlanningSessionController(
    private val planningSessionService: PlanningSessionService,
    private val backlogTaskService: BacklogTaskService,
) {

    @GetMapping("/plans/current")
    fun getCurrentPlan(
        @AuthenticationPrincipal principal: TaskerPrincipal,
    ): ResponseEntity<CurrentPlanResponse> {
        val session = planningSessionService.findCurrentPlan(principal.userId)
            ?: return ResponseEntity.noContent().build()
        val sessionId = session.id ?: return ResponseEntity.noContent().build()

        val tasks = backlogTaskService
            .getTasksScheduledInSession(principal.userId, sessionId)
            .map { it.toResponse() }

        val weekStart = session.weekStart!!
        val weekEnd = weekStart.plusDays(6)

        return ResponseEntity.ok(
            CurrentPlanResponse(
                id = sessionId.toString(),
                status = session.status.name.lowercase(),
                startedAt = session.startedAt.toString(),
                endedAt = session.endedAt?.toString(),
                summary = session.summary,
                tasks = tasks,
                weekStart = weekStart.toString(),
                weekEnd = weekEnd.toString(),
            )
        )
    }
}
