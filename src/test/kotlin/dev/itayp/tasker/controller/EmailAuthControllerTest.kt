package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.service.EmailLoginResult
import dev.itayp.tasker.service.EmailLoginService
import dev.itayp.tasker.service.LocaleNegotiationService
import dev.itayp.tasker.service.RegistrationHints
import dev.itayp.tasker.service.RegistrationHintsResolver
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
// LocaleNegotiationService is imported for real, not mocked: it is a stateless lookup over a
// fixed list, and a mock returning null would quietly hide the `lang`-beats-header precedence.
@Import(SecurityConfiguration::class, LocaleNegotiationService::class, RegistrationHintsResolver::class)
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

    // ── Language precedence ─────────────────────────────────────────────────

    @Test
    fun `an explicit lang beats the browser header when sending a link`() {
        mockMvc.perform(
            post("/api/auth/email?lang=he")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"someone@example.com"}""")
                .header("Accept-Language", "en-US,en;q=0.9")
        )
            .andExpect(status().isNoContent)

        // The visitor read the site in Hebrew, so the magic-link email is Hebrew too.
        verify(emailLoginService).requestLogin("someone@example.com", null, "he")
    }

    @Test
    fun `the browser header still applies when no lang is given`() {
        mockMvc.perform(
            post("/api/auth/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"someone@example.com"}""")
                .header("Accept-Language", "he-IL,he;q=0.9")
        )
            .andExpect(status().isNoContent)

        verify(emailLoginService).requestLogin("someone@example.com", null, "he")
    }

    @Test
    fun `an unsupported lang falls through to the header rather than failing the request`() {
        mockMvc.perform(
            post("/api/auth/email?lang=../../etc/passwd")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"someone@example.com"}""")
                .header("Accept-Language", "ru")
        )
            .andExpect(status().isNoContent)

        verify(emailLoginService).requestLogin("someone@example.com", null, "ru")
    }

    // ── Registration hints ──────────────────────────────────────────────────

    @Test
    fun `POST callback passes the visitor's language and browser time zone to registration`() {
        val hints = RegistrationHints(language = "he", timeZone = "Asia/Jerusalem")
        whenever(emailLoginService.completeLogin("good-token", hints)).thenReturn(EmailLoginResult.Success(userEntity()))

        mockMvc.perform(
            post("/api/auth/email/callback?lang=he&tz=Asia/Jerusalem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"good-token"}""")
                .header("Accept-Language", "en-US")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("success"))
    }

    @Test
    fun `POST callback drops an unsupported time zone instead of failing`() {
        whenever(emailLoginService.completeLogin("good-token", RegistrationHints(language = "ru")))
            .thenReturn(EmailLoginResult.Success(userEntity()))

        mockMvc.perform(
            post("/api/auth/email/callback?tz=Mars/Olympus_Mons")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"good-token"}""")
                .header("Accept-Language", "ru")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("success"))
    }
}
