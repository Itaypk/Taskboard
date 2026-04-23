package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.SessionAuthenticator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.TelegramAuthData
import dev.itayp.tasker.service.TelegramAuthException
import dev.itayp.tasker.service.TelegramAuthService
import dev.itayp.tasker.service.UserAuthService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Optional
import java.util.UUID

@WebMvcTest(AuthController::class)
@Import(SecurityConfiguration::class)
class AuthControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var telegramAuthService: TelegramAuthService
    @MockitoBean lateinit var userAuthService: UserAuthService
    @MockitoBean lateinit var userRepository: UserRepository
    @MockitoBean lateinit var sessionAuthenticator: SessionAuthenticator

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun userEntity() = UserEntity().apply {
        id = userId
        telegramId = 42L
        telegramUsername = "alice"
        telegramFirstName = "Alice"
    }

    @Test
    fun `POST telegram returns 200 and logs in user on valid payload`() {
        val data = TelegramAuthData(42L, "alice", "Alice", null, Instant.parse("2026-04-21T12:00:00Z"))
        whenever(telegramAuthService.verify(any())).thenReturn(data)
        whenever(userAuthService.loginOrRegisterByTelegram(data)).thenReturn(userEntity())

        mockMvc.perform(
            post("/api/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"42","auth_date":"1","hash":"x"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.telegramUsername").value("alice"))
    }

    @Test
    fun `POST telegram returns 401 when HMAC verification fails`() {
        whenever(telegramAuthService.verify(any())).thenThrow(TelegramAuthException("Bad HMAC"))

        mockMvc.perform(
            post("/api/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"42","auth_date":"1","hash":"x"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("telegram_auth_failed"))
    }

    @Test
    fun `GET me without auth returns 401`() {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me with auth returns current user JSON`() {
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(userEntity()))

        mockMvc.perform(get("/api/auth/me").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.telegramUsername").value("alice"))
    }
}
