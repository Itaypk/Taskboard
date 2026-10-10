package dev.itayp.tasker.controller

import dev.itayp.nescioquid.telegram.TelegramAuthData
import dev.itayp.nescioquid.telegram.TelegramAuthorizationRequest
import dev.itayp.nescioquid.telegram.TelegramOidcService
import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.LinkResult
import dev.itayp.tasker.service.LocaleNegotiationService
import dev.itayp.tasker.service.RegistrationHints
import dev.itayp.tasker.service.RegistrationHintsResolver
import dev.itayp.tasker.service.UserAuthService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(TelegramOidcController::class)
@Import(SecurityConfiguration::class, LocaleNegotiationService::class, RegistrationHintsResolver::class)
@TestPropertySource(properties = ["tasker.telegram.bot-username=BacklogFyiBot"])
class TelegramOidcControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var telegramOidcService: TelegramOidcService
    @MockitoBean lateinit var userAuthService: UserAuthService
    @MockitoBean lateinit var accountLinkService: AccountLinkService
    @MockitoBean lateinit var sessionAuthenticator: SessionAuthenticator

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private val authzUrl = "https://oauth.telegram.org/auth?client_id=1&state=ST"
    private val data = TelegramAuthData(42L, "alice", "Alice", null, Instant.parse("2026-04-21T12:00:00Z"))

    private fun oauthSession(mode: String, state: String = "ST", next: String = "/") = MockHttpSession().apply {
        setAttribute("tgOauthMode", mode)
        setAttribute("tgOauthState", state)
        setAttribute("tgOauthVerifier", "VR")
        setAttribute("tgOauthNext", next)
    }

    private fun configured() {
        whenever(telegramOidcService.isConfigured()).thenReturn(true)
        whenever(telegramOidcService.buildAuthorizationRequest(any()))
            .thenReturn(TelegramAuthorizationRequest(authzUrl, "ST", "VR"))
    }

    @Test
    fun `start redirects to the Telegram authorize url`() {
        configured()
        mockMvc.perform(get("/api/auth/telegram/start"))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl(authzUrl))
    }

    @Test
    fun `start redirects with a notice when Telegram is not configured`() {
        whenever(telegramOidcService.isConfigured()).thenReturn(false)
        mockMvc.perform(get("/api/auth/telegram/start"))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?telegramLogin=unavailable"))
    }

    @Test
    fun `link start requires authentication`() {
        mockMvc.perform(get("/api/auth/telegram/link/start"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `link start redirects to Telegram for an authenticated user`() {
        configured()
        mockMvc.perform(get("/api/auth/telegram/link/start").with(authentication(auth)))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl(authzUrl))
    }

    @Test
    fun `callback logs the user in and redirects to next`() {
        whenever(telegramOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(userAuthService.loginOrRegisterByTelegram(data))
            .thenReturn(UserEntity().apply { id = userId })

        mockMvc.perform(
            get("/api/auth/telegram/callback").param("code", "CODE").param("state", "ST")
                .session(oauthSession("login", next = "/")),
        )
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/"))
    }

    @Test
    fun `start keeps a supported browser time zone for the callback and drops anything else`() {
        configured()
        val session = MockHttpSession()
        mockMvc.perform(get("/api/auth/telegram/start").param("tz", "Asia/Jerusalem").session(session))
            .andExpect(status().isFound)
        assertThat(session.getAttribute("tgOauthTimeZone")).isEqualTo("Asia/Jerusalem")

        val other = MockHttpSession()
        mockMvc.perform(get("/api/auth/telegram/start").param("tz", "Mars/Olympus_Mons").session(other))
            .andExpect(status().isFound)
        assertThat(other.getAttribute("tgOauthTimeZone")).isNull()
    }

    @Test
    fun `callback registers with the language header and the time zone kept at start`() {
        whenever(telegramOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(userAuthService.loginOrRegisterByTelegram(data, RegistrationHints(language = "he", timeZone = "Asia/Jerusalem")))
            .thenReturn(UserEntity().apply { id = userId })
        val session = oauthSession("login").apply { setAttribute("tgOauthTimeZone", "Asia/Jerusalem") }

        mockMvc.perform(
            get("/api/auth/telegram/callback").param("code", "CODE").param("state", "ST")
                .header("Accept-Language", "he-IL")
                .session(session),
        )
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/"))
        assertThat(session.getAttribute("tgOauthTimeZone")).isNull()
    }

    @Test
    fun `callback rejects a state mismatch`() {
        mockMvc.perform(
            get("/api/auth/telegram/callback").param("code", "CODE").param("state", "WRONG")
                .session(oauthSession("login")),
        )
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?telegramLogin=failed"))
    }

    @Test
    fun `callback links Telegram in link mode`() {
        whenever(telegramOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(accountLinkService.linkTelegram(userId, data)).thenReturn(LinkResult.Success)

        mockMvc.perform(
            get("/api/auth/telegram/callback").param("code", "CODE").param("state", "ST")
                .session(oauthSession("link"))
                .with(authentication(auth)),
        )
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/settings?telegramLink=success"))
    }

    @Test
    fun `callback surfaces a conflict when Telegram belongs to another account`() {
        whenever(telegramOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(accountLinkService.linkTelegram(userId, data)).thenReturn(LinkResult.ConflictOwnedByAnother)

        mockMvc.perform(
            get("/api/auth/telegram/callback").param("code", "CODE").param("state", "ST")
                .session(oauthSession("link"))
                .with(authentication(auth)),
        )
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/settings?telegramLink=conflict"))
    }

    /**
     * The handle the SPA needs for the post-link "open the chat" step — linking authenticates the
     * account but leaves the bot unable to write until the user opens the chat themselves.
     */
    @Test
    fun `bot exposes the configured username to a signed-in user`() {
        mockMvc.perform(get("/api/auth/telegram/bot").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("BacklogFyiBot"))
    }

    @Test
    fun `bot requires authentication`() {
        mockMvc.perform(get("/api/auth/telegram/bot"))
            .andExpect(status().isUnauthorized)
    }
}
