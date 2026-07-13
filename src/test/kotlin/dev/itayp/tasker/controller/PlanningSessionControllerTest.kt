package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlannedTaskEntity
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import dev.itayp.tasker.planning.PlanFinalizationService
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.SortKeyGenerator
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@WebMvcTest(PlanningSessionController::class)
@Import(SecurityConfiguration::class)
class PlanningSessionControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var planningSessionService: PlanningSessionService

    @MockitoBean
    lateinit var backlogTaskService: BacklogTaskService

    @MockitoBean
    lateinit var plannedTaskRepository: PlannedTaskRepository

    @MockitoBean
    lateinit var plannedTaskSlotRepository: PlannedTaskSlotRepository

    @MockitoBean
    lateinit var planFinalizationService: PlanFinalizationService

    @MockitoBean
    lateinit var oneOffEventService: dev.itayp.tasker.oneoff.OneOffEventService

    @MockitoBean
    lateinit var userCryptoService: UserCryptoService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val plannedTaskId = UUID.fromString("00000000-0000-0000-0000-000000000003")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun aSession(status: PlanningSessionStatus = PlanningSessionStatus.ACTIVE) = PlanningSession(
        id = sessionId,
        userId = userId,
        conversationId = null,
        status = status,
        startedAt = Instant.parse("2026-05-01T12:00:00Z"),
        weekStart = LocalDate.parse("2026-04-27"),
        endedAt = if (status == PlanningSessionStatus.COMPLETED) Instant.parse("2026-05-01T13:00:00Z") else null,
        summary = if (status == PlanningSessionStatus.COMPLETED) "Agreed plan summary." else null,
    )

    private fun aPlannedTaskEntity() = PlannedTaskEntity().apply {
        this.id = plannedTaskId
        this.sessionId = this@PlanningSessionControllerTest.sessionId
        this.userId = this@PlanningSessionControllerTest.userId
        this.backlogTaskId = taskId
        this.title = "Planned task".toByteArray(Charsets.UTF_8)
        this.position = 0
    }

    private fun aTask() = BacklogTask(
        id = taskId,
        boardId = userId,
        assigneeUserId = null,
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
        relevantFrom = null,
    )

    // ── GET /plans/current ───────────────────────────────────────────────────

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
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId))
            .thenReturn(listOf(aPlannedTaskEntity()))
        whenever(plannedTaskSlotRepository.findAllByPlannedTaskIdIn(listOf(plannedTaskId)))
            .thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/plans/current").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.endedAt").doesNotExist())
            .andExpect(jsonPath("$.tasks[0].task.title").value("Planned task"))
            .andExpect(jsonPath("$.tasks[0].task.lastScheduledInSessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.tasks[0].slots").isArray)
            // weekStart is the stored Monday of the week (independent of startedAt)
            .andExpect(jsonPath("$.weekStart").value("2026-04-27"))
            .andExpect(jsonPath("$.weekEnd").value("2026-05-03"))
    }

    @Test
    fun `GET plans-current returns 200 with completed fallback`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession(PlanningSessionStatus.COMPLETED))
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId))
            .thenReturn(emptyList())
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId))
            .thenReturn(emptyList())
        whenever(plannedTaskSlotRepository.findAllByPlannedTaskIdIn(emptyList()))
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

    // ── GET /plans/week/{weekStart} ──────────────────────────────────────────

    @Test
    fun `GET plans-week returns 204 when the week has no plan`() {
        val week = LocalDate.parse("2026-06-01")
        whenever(planningSessionService.findPlanForWeek(userId, week)).thenReturn(null)

        mockMvc.perform(get("/api/v1/plans/week/2026-06-01").with(authentication(auth)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET plans-week returns 200 with the week's plan`() {
        val week = LocalDate.parse("2026-04-27")
        whenever(planningSessionService.findPlanForWeek(userId, week))
            .thenReturn(aSession(PlanningSessionStatus.COMPLETED))
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId)).thenReturn(listOf(aTask()))
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId))
            .thenReturn(listOf(aPlannedTaskEntity()))
        whenever(plannedTaskSlotRepository.findAllByPlannedTaskIdIn(listOf(plannedTaskId))).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/plans/week/2026-04-27").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.weekStart").value("2026-04-27"))
            .andExpect(jsonPath("$.weekEnd").value("2026-05-03"))
            .andExpect(jsonPath("$.tasks[0].task.title").value("Planned task"))
    }

    // ── GET /plans ───────────────────────────────────────────────────────────

    @Test
    fun `GET plans returns the finalized-plan index`() {
        whenever(planningSessionService.listFinalizedPlans(userId)).thenReturn(listOf(
            dev.itayp.tasker.planning.FinalizedPlanSummary(aSession(PlanningSessionStatus.COMPLETED), taskCount = 4),
        ))

        mockMvc.perform(get("/api/v1/plans").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(sessionId.toString()))
            .andExpect(jsonPath("$[0].weekStart").value("2026-04-27"))
            .andExpect(jsonPath("$[0].weekEnd").value("2026-05-03"))
            .andExpect(jsonPath("$[0].status").value("completed"))
            .andExpect(jsonPath("$[0].taskCount").value(4))
            .andExpect(jsonPath("$[0].hasSummary").value(true))
    }

    // ── POST /plans/current/tasks/{taskId} ───────────────────────────────────

    @Test
    fun `POST add task to plan returns 204 and delegates to planFinalizationService`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession())
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())

        mockMvc.perform(
            post("/api/v1/plans/current/tasks/$taskId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isNoContent)

        verify(planFinalizationService).addTaskToSession(
            eq(userId),
            eq(sessionId),
            eq(AgreedPlanTask(taskId = taskId, title = "Planned task", slots = listOf(AgreedTimeSlot("2026-04-28T09:00:00Z", "2026-04-28T10:00:00Z")))),
        )
    }

    @Test
    fun `POST add task to plan returns 422 when no current plan exists`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/plans/current/tasks/$taskId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `POST add task to plan returns 404 when task does not belong to user`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession())
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/plans/current/tasks/$taskId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isNotFound)
    }

    // ── PUT /plans/current/tasks/{taskId}/slot ───────────────────────────────

    @Test
    fun `PUT change slot returns 204 and delegates to planFinalizationService`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession())
        whenever(planFinalizationService.changeTaskSlot(eq(userId), eq(sessionId), eq(taskId), eq(AgreedTimeSlot("2026-04-28T09:00:00Z", "2026-04-28T10:00:00Z"))))
            .thenReturn(true)

        mockMvc.perform(
            put("/api/v1/plans/current/tasks/$taskId/slot")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isNoContent)

        verify(planFinalizationService).changeTaskSlot(
            eq(userId), eq(sessionId), eq(taskId),
            eq(AgreedTimeSlot("2026-04-28T09:00:00Z", "2026-04-28T10:00:00Z")),
        )
    }

    @Test
    fun `PUT change slot returns 422 when no current plan exists`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)

        mockMvc.perform(
            put("/api/v1/plans/current/tasks/$taskId/slot")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `PUT change slot returns 404 when the task is not in the plan`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(aSession())
        whenever(planFinalizationService.changeTaskSlot(eq(userId), eq(sessionId), eq(taskId), any()))
            .thenReturn(false)

        mockMvc.perform(
            put("/api/v1/plans/current/tasks/$taskId/slot")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `PUT change slot without csrf returns 403`() {
        mockMvc.perform(
            put("/api/v1/plans/current/tasks/$taskId/slot")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startIso":"2026-04-28T09:00:00Z","endIso":"2026-04-28T10:00:00Z"}"""),
        ).andExpect(status().isForbidden)
    }

    // ── POST /plans/events/{eventId}/cancel ──────────────────────────────────

    @Test
    fun `POST cancel event returns 204 when cancelled`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
        val event = dev.itayp.tasker.oneoff.OneOffEvent(
            id = eventId, userId = userId, boardId = userId, title = "Dentist",
            startsAt = Instant.parse("2026-08-01T09:00:00Z"), endsAt = Instant.parse("2026-08-01T10:00:00Z"),
            location = null, notes = null, icalUid = "uid", cancelledAt = Instant.parse("2026-07-01T12:00:00Z"),
        )
        whenever(oneOffEventService.cancelEvent(userId, eventId))
            .thenReturn(dev.itayp.tasker.oneoff.CancelOutcome.Cancelled(event))

        mockMvc.perform(post("/api/v1/plans/events/$eventId/cancel").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `POST cancel event returns 204 when already cancelled`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
        whenever(oneOffEventService.cancelEvent(userId, eventId))
            .thenReturn(dev.itayp.tasker.oneoff.CancelOutcome.AlreadyCancelled)

        mockMvc.perform(post("/api/v1/plans/events/$eventId/cancel").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `POST cancel event returns 404 when not found`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000e3")
        whenever(oneOffEventService.cancelEvent(userId, eventId))
            .thenReturn(dev.itayp.tasker.oneoff.CancelOutcome.NotFound)

        mockMvc.perform(post("/api/v1/plans/events/$eventId/cancel").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST cancel event returns 409 when already started`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000e4")
        whenever(oneOffEventService.cancelEvent(userId, eventId))
            .thenReturn(dev.itayp.tasker.oneoff.CancelOutcome.AlreadyStarted)

        mockMvc.perform(post("/api/v1/plans/events/$eventId/cancel").with(authentication(auth)).with(csrf()))
            .andExpect(status().isConflict)
    }

    @Test
    fun `POST cancel event without csrf returns 403`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000e5")

        mockMvc.perform(post("/api/v1/plans/events/$eventId/cancel").with(authentication(auth)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST cancel event unauthenticated returns 401`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000e6")

        mockMvc.perform(post("/api/v1/plans/events/$eventId/cancel").with(csrf()))
            .andExpect(status().isUnauthorized)
    }
}
