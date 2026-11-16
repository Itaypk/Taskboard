package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.metrics.LoginMetrics
import dev.itayp.tasker.metrics.LoginMode
import dev.itayp.tasker.metrics.LoginOutcome
import dev.itayp.tasker.metrics.LoginProvider
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.security.google.GoogleAuthData
import dev.itayp.tasker.security.google.GoogleAuthException
import dev.itayp.tasker.security.google.GoogleAuthorizationRequest
import dev.itayp.tasker.security.google.GoogleOidcService
import dev.itayp.tasker.service.AccountLinkService
import dev.itayp.tasker.service.GoogleLoginOutcome
import dev.itayp.tasker.service.LinkResult
import dev.itayp.tasker.service.LocaleNegotiationService
import dev.itayp.tasker.service.RegistrationClosedException
import dev.itayp.tasker.service.RegistrationHintsResolver
import dev.itayp.tasker.service.UserAuthService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(GoogleOidcController::class)
@Import(SecurityConfiguration::class, LocaleNegotiationService::class, RegistrationHintsResolver::class)
// A trailing slash, as an operator might write it; the redirect URI must still come out with a single one.
@TestPropertySource(properties = ["tasker.app.base-url=http://localhost:5173/"])
class GoogleOidcControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var googleOidcService: GoogleOidcService
    @MockitoBean lateinit var userAuthService: UserAuthService
    @MockitoBean lateinit var accountLinkService: AccountLinkService
    @MockitoBean lateinit var sessionAuthenticator: SessionAuthenticator
    @MockitoBean lateinit var loginMetrics: LoginMetrics

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private val authzUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=c&state=ST"
    private val data = GoogleAuthData("g-123", "alice@gmail.com", emailVerified = true, hostedDomain = null)

    private fun oauthSession(mode: String, next: String = "/") = MockHttpSession().apply {
        setAttribute("googleOauthMode", mode)
        setAttribute("googleOauthState", "ST")
        setAttribute("googleOauthVerifier", "VR")
        setAttribute("googleOauthNext", next)
    }

    private fun callback(session: MockHttpSession) =
        get("/api/auth/google/callback").param("code", "CODE").param("state", "ST").session(session)

    private fun configured() {
        whenever(googleOidcService.isConfigured()).thenReturn(true)
        whenever(googleOidcService.buildAuthorizationRequest("http://localhost:5173/api/auth/google/callback"))
            .thenReturn(GoogleAuthorizationRequest(authzUrl, "ST", "VR"))
    }

    @Test
    fun `start redirects to Google and keeps the round-trip in the session`() {
        configured()
        val session = MockHttpSession()
        mockMvc.perform(get("/api/auth/google/start").param("next", "/settings").param("lang", "he").session(session))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl(authzUrl))
        assertThat(session.getAttribute("googleOauthState")).isEqualTo("ST")
        assertThat(session.getAttribute("googleOauthMode")).isEqualTo("login")
        assertThat(session.getAttribute("googleOauthNext")).isEqualTo("/settings")
        assertThat(session.getAttribute("googleOauthLanguage")).isEqualTo("he")
    }

    @Test
    fun `start redirects with a notice when Google is not configured`() {
        whenever(googleOidcService.isConfigured()).thenReturn(false)
        mockMvc.perform(get("/api/auth/google/start"))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?googleLogin=unavailable"))
    }

    @Test
    fun `link start requires authentication`() {
        mockMvc.perform(get("/api/auth/google/link/start"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `callback logs the user in and redirects to next`() {
        whenever(googleOidcService.completeAuthorization(eq("CODE"), eq("VR"), any()))
            .thenReturn(data)
        whenever(userAuthService.loginByGoogle(eq(data), any()))
            .thenReturn(GoogleLoginOutcome.Success(UserEntity().apply { id = userId }))
        val session = oauthSession("login", next = "/settings")

        mockMvc.perform(callback(session))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/settings"))
        verify(sessionAuthenticator).authenticate(any(), any(), any())
        verify(loginMetrics).record(LoginProvider.GOOGLE, LoginOutcome.SUCCESS, LoginMode.LOGIN)
        assertThat(session.getAttribute("googleOauthState")).isNull()
    }

    @Test
    fun `callback explains an email that belongs to an account it can't sign into`() {
        whenever(googleOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(userAuthService.loginByGoogle(any(), any())).thenReturn(GoogleLoginOutcome.EmailConflict)

        mockMvc.perform(callback(oauthSession("login")))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?googleLogin=emailConflict"))
        verify(sessionAuthenticator, never()).authenticate(any(), any(), any())
        verify(loginMetrics).record(LoginProvider.GOOGLE, LoginOutcome.REJECTED, LoginMode.LOGIN)
    }

    @Test
    fun `callback reports closed registration`() {
        whenever(googleOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(userAuthService.loginByGoogle(any(), any())).thenThrow(RegistrationClosedException())

        mockMvc.perform(callback(oauthSession("login")))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?googleLogin=closed"))
    }

    @Test
    fun `callback counts a failed exchange as an error`() {
        whenever(googleOidcService.completeAuthorization(any(), any(), any())).thenThrow(GoogleAuthException("boom"))

        mockMvc.perform(callback(oauthSession("login")))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?googleLogin=failed"))
        verify(loginMetrics).record(LoginProvider.GOOGLE, LoginOutcome.ERROR, LoginMode.LOGIN)
    }

    @Test
    fun `callback rejects a state mismatch without calling Google`() {
        mockMvc.perform(
            get("/api/auth/google/callback").param("code", "CODE").param("state", "WRONG").session(oauthSession("login")),
        )
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?googleLogin=failed"))
        verify(googleOidcService, never()).completeAuthorization(any(), any(), any())
    }

    @Test
    fun `a Telegram round-trip can't be completed through the Google callback`() {
        val telegramSession = MockHttpSession().apply {
            setAttribute("tgOauthMode", "login")
            setAttribute("tgOauthState", "ST")
            setAttribute("tgOauthVerifier", "VR")
        }
        mockMvc.perform(callback(telegramSession))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/?googleLogin=failed"))
        verify(googleOidcService, never()).completeAuthorization(any(), any(), any())
    }

    @Test
    fun `callback links Google in link mode`() {
        whenever(googleOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(accountLinkService.linkGoogle(userId, data)).thenReturn(LinkResult.Success)

        mockMvc.perform(callback(oauthSession("link")).with(authentication(auth)))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/settings?googleLink=success"))
        verify(loginMetrics).record(LoginProvider.GOOGLE, LoginOutcome.SUCCESS, LoginMode.LINK)
    }

    @Test
    fun `callback surfaces a conflict when Google belongs to another account`() {
        whenever(googleOidcService.completeAuthorization(any(), any(), any())).thenReturn(data)
        whenever(accountLinkService.linkGoogle(userId, data)).thenReturn(LinkResult.ConflictOwnedByAnother)

        mockMvc.perform(callback(oauthSession("link")).with(authentication(auth)))
            .andExpect(status().isFound)
            .andExpect(redirectedUrl("/settings?googleLink=conflict"))
    }
}
