package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlanningSessionEntity
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.SortKeyGenerator
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(PlanningSessionController::class)
@Import(SecurityConfiguration::class)
class PlanningSessionControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var planningSessionService: PlanningSessionService

    @MockitoBean
    lateinit var backlogTaskService: BacklogTaskService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun aSession(status: PlanningSessionStatus = PlanningSessionStatus.ACTIVE) = PlanningSessionEntity().apply {
        this.id = sessionId
        this.userId = this@PlanningSessionControllerTest.userId
        this.status = status
        this.startedAt = Instant.parse("2026-05-01T12:00:00Z")
        this.endedAt = if (status == PlanningSessionStatus.COMPLETED) Instant.parse("2026-05-01T13:00:00Z") else null
        this.summary = if (status == PlanningSessionStatus.COMPLETED) "Agreed plan summary." else null
    }

    private fun aTask() = BacklogTask(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        userId = userId,
        title = "Planned task",
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = TaskStatus.TODO,
        category = BacklogTaskCategory(categoryId, userId, "Work", CategoryColor.SUNSHINE),
        tags = emptySet(),
        sortKey = SortKeyGenerator.INITIAL,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = sessionId,
    )

    @Test
    fun `GET plans-current returns 204 when no current plan`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)

        mockMvc.perform(get("/api/v1/plans/current").with(authentication(auth)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET plans-current returns 200 with active session and tasks`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession(PlanningSessionStatus.ACTIVE))
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId))
            .thenReturn(listOf(aTask()))

        mockMvc.perform(get("/api/v1/plans/current").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.endedAt").doesNotExist())
            .andExpect(jsonPath("$.tasks[0].title").value("Planned task"))
            .andExpect(jsonPath("$.tasks[0].lastScheduledInSessionId").value(sessionId.toString()))
    }

    @Test
    fun `GET plans-current returns 200 with completed fallback`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession(PlanningSessionStatus.COMPLETED))
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId))
            .thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/plans/current").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.summary").value("Agreed plan summary."))
            .andExpect(jsonPath("$.tasks").isArray)
            .andExpect(jsonPath("$.tasks").isEmpty)
    }

    @Test
    fun `GET plans-current unauthenticated returns 401`() {
        mockMvc.perform(get("/api/v1/plans/current"))
            .andExpect(status().isUnauthorized)
    }
}
