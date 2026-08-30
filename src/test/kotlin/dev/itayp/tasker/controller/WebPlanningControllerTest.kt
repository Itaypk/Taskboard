package dev.itayp.tasker.controller

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.PlanningTranscriptService
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserSettingsService
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@WebMvcTest(WebPlanningController::class)
@Import(SecurityConfiguration::class)
class WebPlanningControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var orchestrator: WeeklyPlanningOrchestrator

    @MockitoBean
    lateinit var planningSessionService: PlanningSessionService

    @MockitoBean
    lateinit var transcriptService: PlanningTranscriptService

    @MockitoBean
    lateinit var userSettingsService: UserSettingsService

    @MockitoBean
    lateinit var aiAccessService: AiAccessService

    @MockitoBean
    lateinit var clock: Clock

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    /** Stubs the inputs `resolveWeekStart()` needs: clock.withZone(zone) then LocalDate.now(thatClock). */
    private fun stubWeekResolution() {
        whenever(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-06-02T10:00:00Z"), ZoneOffset.UTC))
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings())
    }

    @Test
    fun `entry returns week options when nothing is in flight`() {
        stubWeekResolution()
        whenever(planningSessionService.findActiveSession(userId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)

        mockMvc.perform(get("/api/v1/planning/entry").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeSessionId").value(nullValue()))
            .andExpect(jsonPath("$.revisableSessionId").value(nullValue()))
            // 2026-06-02 is a Tuesday; Monday-start week begins 2026-06-01.
            .andExpect(jsonPath("$.thisWeek.weekStart").value("2026-06-01"))
            .andExpect(jsonPath("$.thisWeek.weekEnd").value("2026-06-07"))
            .andExpect(jsonPath("$.nextWeek.weekStart").value("2026-06-08"))
            .andExpect(jsonPath("$.aiAvailable").value(true))
    }

    @Test
    fun `entry surfaces a completed plan as revisable`() {
        stubWeekResolution()
        whenever(planningSessionService.findActiveSession(userId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId))
            .thenReturn(session(PlanningSessionStatus.COMPLETED, summary = "Last week recap"))
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)

        mockMvc.perform(get("/api/v1/planning/entry").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.completedPlanSummary").value("Last week recap"))
            .andExpect(jsonPath("$.revisableSessionId").value(sessionId.toString()))
    }

    @Test
    fun `entry reports aiAvailable=false when AI is unavailable to the caller`() {
        stubWeekResolution()
        whenever(planningSessionService.findActiveSession(userId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)

        mockMvc.perform(get("/api/v1/planning/entry").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.aiAvailable").value(false))
    }

    @Test
    fun `start drives the orchestrator and returns buffered messages`() {
        stubWeekResolution()
        whenever(orchestrator.start(any(), any(), any())).thenAnswer {
            val channel = it.getArgument<ConversationChannel>(1)
            channel.send(ChannelMessage.Text("How heavy is your week?"))
            sessionId
        }
        whenever(orchestrator.phase(sessionId)).thenReturn(WeeklyPlanningOrchestrator.Phase.AWAITING_CAPACITY)

        mockMvc.perform(
            post("/api/v1/planning/start")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"offset":"CURRENT"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.phase").value("AWAITING_CAPACITY"))
            .andExpect(jsonPath("$.messages[0].type").value("text"))
            .andExpect(jsonPath("$.messages[0].text").value("How heavy is your week?"))
    }

    @Test
    fun `transcript returns the reconstructed conversation`() {
        whenever(planningSessionService.findById(userId, sessionId))
            .thenReturn(session(PlanningSessionStatus.ACTIVE, summary = null))
        whenever(orchestrator.phase(sessionId)).thenReturn(WeeklyPlanningOrchestrator.Phase.CONVERSING)
        whenever(orchestrator.conversationId(sessionId)).thenReturn(UUID.randomUUID())
        whenever(transcriptService.reconstruct(any())).thenReturn(
            listOf(
                TranscriptMessage("assistant", "text", "Welcome back!"),
                TranscriptMessage("user", "text", "Monday"),
            )
        )

        mockMvc.perform(get("/api/v1/planning/$sessionId").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.phase").value("CONVERSING"))
            .andExpect(jsonPath("$.messages[0].role").value("assistant"))
            .andExpect(jsonPath("$.messages[0].text").value("Welcome back!"))
            .andExpect(jsonPath("$.messages[1].role").value("user"))
            .andExpect(jsonPath("$.messages[1].text").value("Monday"))
    }

    @Test
    fun `transcript returns 409 when the session has no in-memory state`() {
        whenever(planningSessionService.findById(userId, sessionId))
            .thenReturn(session(PlanningSessionStatus.ACTIVE, summary = null))
        whenever(orchestrator.phase(sessionId)).thenReturn(null)

        mockMvc.perform(get("/api/v1/planning/$sessionId").with(authentication(auth)))
            .andExpect(status().isConflict)
    }

    @Test
    fun `transcript returns 404 for a session the user does not own`() {
        whenever(planningSessionService.findById(userId, sessionId)).thenReturn(null)

        mockMvc.perform(get("/api/v1/planning/$sessionId").with(authentication(auth)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `reply without a CSRF token is rejected`() {
        mockMvc.perform(
            post("/api/v1/planning/$sessionId/reply")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"hi"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `reply to a session with no in-memory state returns 409`() {
        whenever(planningSessionService.findById(userId, sessionId))
            .thenReturn(session(PlanningSessionStatus.ACTIVE, summary = null))
        whenever(orchestrator.phase(sessionId)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/planning/$sessionId/reply")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"hi"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `reply to a session the user does not own returns 404`() {
        whenever(planningSessionService.findById(userId, sessionId)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/planning/$sessionId/reply")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"hi"}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `revise on a non-completed session returns 409`() {
        doThrow(IllegalStateException("Cannot revise session in status ACTIVE"))
            .whenever(orchestrator).startRevision(any(), any(), any(), anyOrNull())

        mockMvc.perform(
            post("/api/v1/planning/$sessionId/revise")
                .with(authentication(auth)).with(csrf()),
        ).andExpect(status().isConflict)
    }

    private fun settings() = UserSettings(
        userId = userId,
        displayName = "Test",
        contextBlock = null,
        timeZone = "UTC",
        preferredLanguage = "en-US",
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = "MONDAY",
        autoArchiveDays = null,
    )

    private fun session(status: PlanningSessionStatus, summary: String?) = PlanningSession(
        id = sessionId,
        userId = userId,
        conversationId = null,
        status = status,
        startedAt = Instant.parse("2026-06-01T09:00:00Z"),
        weekStart = LocalDate.parse("2026-06-01"),
        endedAt = null,
        summary = summary,
    )
}
