package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountBootstrapSettings
import dev.itayp.tasker.service.UserSettingsService
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
import java.util.Optional
import java.util.UUID

@WebMvcTest(AuthController::class)
@Import(SecurityConfiguration::class)
class AuthControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var userRepository: UserRepository
    @MockitoBean lateinit var userCryptoService: UserCryptoService
    @MockitoBean lateinit var userSettingsService: UserSettingsService

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
        telegramFirstName = "Alice".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `GET me without auth returns 204`() {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET me with auth returns current user JSON`() {
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(userEntity()))
        whenever(userSettingsService.getBootstrapSettings(userId))
            .thenReturn(AccountBootstrapSettings(preferredLanguage = "he", timeZoneDetectionPending = true))

        mockMvc.perform(get("/api/auth/me").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.telegramUsername").value("alice"))
            .andExpect(jsonPath("$.preferredLanguage").value("he"))
            .andExpect(jsonPath("$.timeZoneDetectionPending").value(true))
    }
}
