package dev.itayp.tasker.external

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.oneoff.CancelOutcome
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.DirectPlanResult
import dev.itayp.tasker.planning.DirectPlanService
import dev.itayp.tasker.planning.InviteDeliveryResolver
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningWeek
import dev.itayp.tasker.security.ApiTokenAuthenticationFilter
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.ApiTokenService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Slice test for the plan endpoints: argument parsing, error shapes and scope wiring. */
@WebMvcTest(ExternalPlanController::class)
@Import(SecurityConfiguration::class)
class ExternalPlanControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var directPlanService: DirectPlanService
    @MockitoBean lateinit var planningSessionService: PlanningSessionService
    @MockitoBean lateinit var plannedTaskService: PlannedTaskService
    @MockitoBean lateinit var backlogTaskService: BacklogTaskService
    @MockitoBean lateinit var boardService: BoardService
    @MockitoBean lateinit var oneOffEventService: OneOffEventService
    @MockitoBean lateinit var inviteDeliveryResolver: InviteDeliveryResolver
    @MockitoBean lateinit var apiTokenService: ApiTokenService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val weekStart = LocalDate.parse("2026-11-16")

    private fun authWith(vararg authorities: String) = authentication(
        UsernamePasswordAuthenticationToken(
            TaskerPrincipal(userId), null,
            (listOf("ROLE_USER") + authorities).map { SimpleGrantedAuthority(it) },
        )
    )

    private val planAuth = authWith(
        ApiTokenAuthenticationFilter.EXTERNAL_READ,
        ApiTokenAuthenticationFilter.EXTERNAL_WRITE,
        ApiTokenAuthenticationFilter.EXTERNAL_PLAN,
    )
    private val writeAuth = authWith(ApiTokenAuthenticationFilter.EXTERNAL_READ, ApiTokenAuthenticationFilter.EXTERNAL_WRITE)

    @BeforeEach
    fun setUp() {
        whenever(planningSessionService.currentWeekStart(userId)).thenReturn(weekStart)
        whenever(directPlanService.week(eq(userId), any())).thenAnswer {
            PlanningWeek(it.getArgument(1), ZoneId.of("Asia/Jerusalem"))
        }
        whenever(oneOffEventService.listForLocalWeek(eq(userId), any())).thenReturn(emptyList())
    }

    @Test
    fun `an unplanned week answers 200 with a null plan and the context to write one`() {
        mockMvc.perform(get("/api/external/v1/plans/week/next").with(planAuth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.weekStart").value("2026-11-23"))
            .andExpect(jsonPath("$.weekEnd").value("2026-11-29"))
            .andExpect(jsonPath("$.timeZone").value("Asia/Jerusalem"))
            .andExpect(jsonPath("$.calendarDelivery").value("none"))
            .andExpect(jsonPath("$.plan").doesNotExist())
    }

    @Test
    fun `a week that is not a date is a 400 listing what is accepted`() {
        mockMvc.perform(get("/api/external/v1/plans/week/someday").with(planAuth))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("'current', 'next'")))
    }

    @Test
    fun `a submit without tasks is a 400`() {
        mockMvc.perform(
            put("/api/external/v1/plans/week/current").with(planAuth)
                .contentType(MediaType.APPLICATION_JSON).content("""{"note":"hi"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("'tasks' is required")))
    }

    @Test
    fun `a slot without an offset is a 400 naming the field`() {
        mockMvc.perform(
            put("/api/external/v1/plans/week/current").with(planAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tasks":[{"taskId":"$taskId","slots":[{"start":"2026-11-17T09:00","end":"2026-11-17T10:00+02:00"}]}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("tasks[0].slots[0].start")))
        verify(directPlanService, never()).submit(any(), any(), any(), any())
    }

    @Test
    fun `a conflict from the service is a 409 carrying its detail`() {
        whenever(directPlanService.submit(eq(userId), eq(weekStart), any(), anyOrNull()))
            .thenReturn(DirectPlanResult.Rejected(DirectPlanResult.Kind.CONFLICT, "Finish it first."))

        mockMvc.perform(
            put("/api/external/v1/plans/week/current").with(planAuth)
                .contentType(MediaType.APPLICATION_JSON).content("""{"tasks":[]}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value("Finish it first."))
    }

    @Test
    fun `a write-scoped token can't submit a plan`() {
        mockMvc.perform(
            put("/api/external/v1/plans/week/current").with(writeAuth)
                .contentType(MediaType.APPLICATION_JSON).content("""{"tasks":[]}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("scope: plan")))
    }

    @Test
    fun `cancelling an event that already started is a 409`() {
        val eventId = UUID.randomUUID()
        whenever(oneOffEventService.cancelEvent(userId, eventId)).thenReturn(CancelOutcome.AlreadyStarted)

        mockMvc.perform(post("/api/external/v1/events/$eventId/cancel").with(planAuth))
            .andExpect(status().isConflict)
    }
}
