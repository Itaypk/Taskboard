package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.service.EmailLoginResult
import dev.itayp.tasker.service.EmailLoginService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(EmailAuthController::class)
@Import(SecurityConfiguration::class)
class EmailAuthControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var emailLoginService: EmailLoginService
    @MockitoBean lateinit var sessionAuthenticator: SessionAuthenticator

    private fun userEntity() = UserEntity().apply { id = UUID.randomUUID() }

    // ── POST /api/auth/email/callback ────────────────────────────────────────

    @Test
    fun `POST callback returns success outcome and authenticates session`() {
        whenever(emailLoginService.completeLogin("good-token")).thenReturn(EmailLoginResult.Success(userEntity()))

        mockMvc.perform(
            post("/api/auth/email/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"good-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("success"))

        verify(sessionAuthenticator).authenticate(any(), any(), any())
    }

    @Test
    fun `POST callback returns invalid outcome for a consumed or unknown token`() {
        whenever(emailLoginService.completeLogin("bad-token")).thenReturn(EmailLoginResult.Invalid)

        mockMvc.perform(
            post("/api/auth/email/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"bad-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("invalid"))

        verify(sessionAuthenticator, never()).authenticate(any(), any(), any())
    }

    @Test
    fun `POST callback returns unverified outcome for an email conflict`() {
        whenever(emailLoginService.completeLogin("conflict-token")).thenReturn(EmailLoginResult.UnverifiedConflict)

        mockMvc.perform(
            post("/api/auth/email/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"conflict-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("unverified"))

        verify(sessionAuthenticator, never()).authenticate(any(), any(), any())
    }

    // ── GET /api/auth/email/callback (shim) ──────────────────────────────────

    @Test
    fun `GET callback shim redirects to confirm page without consuming the token`() {
        mockMvc.perform(get("/api/auth/email/callback").param("token", "tok123"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", "/email-login?token=tok123"))

        verify(emailLoginService, never()).completeLogin(any(), anyOrNull())
    }

    // ── GET /api/auth/email/precheck ─────────────────────────────────────────

    @Test
    fun `GET precheck returns valid true for a live token without mutating it`() {
        whenever(emailLoginService.precheckToken("live-token")).thenReturn(true)

        mockMvc.perform(get("/api/auth/email/precheck").param("token", "live-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))

        verify(emailLoginService, never()).completeLogin(any(), anyOrNull())
    }

    @Test
    fun `GET precheck returns valid false for an expired token without mutating it`() {
        whenever(emailLoginService.precheckToken("old-token")).thenReturn(false)

        mockMvc.perform(get("/api/auth/email/precheck").param("token", "old-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))

        verify(emailLoginService, never()).completeLogin(any(), anyOrNull())
    }
}
