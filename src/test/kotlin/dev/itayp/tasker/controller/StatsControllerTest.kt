package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.UserStats
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.StatsService
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

@WebMvcTest(StatsController::class)
@Import(SecurityConfiguration::class)
class StatsControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var statsService: StatsService

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    @Test
    fun `returns the user's stats as JSON`() {
        whenever(statsService.computeStats(userId)).thenReturn(
            UserStats(
                joinedAt = Instant.parse("2026-01-15T10:00:00Z"),
                openTasks = 3,
                completedTasks = 12,
                planningSessions = 4,
                avgTasksCreatedPerWeek = 2.5,
                avgTasksCompletedPerWeek = 1.0,
                avgCompletion = Duration.ofDays(3),
            )
        )

        mockMvc.perform(get("/api/v1/stats").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.joinedAt").value("2026-01-15T10:00:00Z"))
            .andExpect(jsonPath("$.openTasks").value(3))
            .andExpect(jsonPath("$.completedTasks").value(12))
            .andExpect(jsonPath("$.planningSessions").value(4))
            .andExpect(jsonPath("$.avgTasksCreatedPerWeek").value(2.5))
            .andExpect(jsonPath("$.avgCompletionSeconds").value(259200))
    }

    @Test
    fun `null completion time serializes as null`() {
        whenever(statsService.computeStats(userId)).thenReturn(
            UserStats(
                joinedAt = null,
                openTasks = 0,
                completedTasks = 0,
                planningSessions = 0,
                avgTasksCreatedPerWeek = 0.0,
                avgTasksCompletedPerWeek = 0.0,
                avgCompletion = null,
            )
        )

        mockMvc.perform(get("/api/v1/stats").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.avgCompletionSeconds").value(null as Any?))
            .andExpect(jsonPath("$.joinedAt").value(null as Any?))
    }

    @Test
    fun `unauthenticated request is rejected`() {
        mockMvc.perform(get("/api/v1/stats"))
            .andExpect(status().isUnauthorized)
    }
}
