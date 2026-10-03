package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.notification.digest.DeadlineReminderMuteService
import dev.itayp.tasker.security.TaskerPrincipal
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(DeadlineReminderMuteController::class)
@Import(SecurityConfiguration::class)
class DeadlineReminderMuteControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var muteService: DeadlineReminderMuteService

    private val userId = UUID.randomUUID()
    private val auth = authentication(
        UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
    )

    @Test
    fun `clears the caller's mutes and reports how many`() {
        whenever(muteService.clearAll(userId)).thenReturn(3)

        mockMvc.perform(delete("/api/v1/settings/deadline-mutes").with(auth).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cleared").value(3))
    }

    @Test
    fun `requires a CSRF token`() {
        mockMvc.perform(delete("/api/v1/settings/deadline-mutes").with(auth))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `requires authentication`() {
        mockMvc.perform(delete("/api/v1/settings/deadline-mutes").with(csrf()))
            .andExpect(status().isUnauthorized)
    }
}
