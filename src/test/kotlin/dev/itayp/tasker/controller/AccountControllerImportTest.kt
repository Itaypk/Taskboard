package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AccountImportService
import dev.itayp.tasker.service.AccountService
import dev.itayp.tasker.service.ImportSummary
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(AccountController::class)
@Import(SecurityConfiguration::class)
class AccountControllerImportTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var accountService: AccountService
    @MockitoBean lateinit var accountImportService: AccountImportService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private val minimalPayload = """
        {
          "formatVersion": 3,
          "exportedAt": "2026-05-25T12:00:00Z",
          "user": { "telegramUsername": null, "telegramFirstName": null, "email": null, "createdAt": null },
          "settings": null,
          "boards": [
            { "name": "My tasks", "role": "OWNER", "categories": [], "tags": [], "tasks": [] }
          ]
        }
    """.trimIndent()

    @Test
    fun `POST import returns 200 with summary on success`() {
        whenever(accountImportService.import(eq(userId), any())).thenReturn(ImportSummary(2, 3, 7))

        mockMvc.perform(
            post("/api/v1/account/import")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalPayload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categories").value(2))
            .andExpect(jsonPath("$.tags").value(3))
            .andExpect(jsonPath("$.tasks").value(7))
    }

    @Test
    fun `POST import returns 401 without authentication`() {
        mockMvc.perform(
            post("/api/v1/account/import")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalPayload)
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST import returns 403 without CSRF token`() {
        mockMvc.perform(
            post("/api/v1/account/import")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalPayload)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST import returns 409 when service reports the account is not empty`() {
        whenever(accountImportService.import(eq(userId), any()))
            .thenThrow(IllegalStateException("Account already has user data"))

        mockMvc.perform(
            post("/api/v1/account/import")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalPayload)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("Account already has user data"))
    }

    @Test
    fun `POST import returns 400 on unsupported formatVersion`() {
        whenever(accountImportService.import(eq(userId), any()))
            .thenThrow(IllegalArgumentException("Unsupported export formatVersion: 99 (expected 3)"))

        mockMvc.perform(
            post("/api/v1/account/import")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalPayload.replace("\"formatVersion\": 3", "\"formatVersion\": 99"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Unsupported export formatVersion: 99 (expected 3)"))
    }

    @Test
    fun `POST import returns 409 on a data-integrity violation instead of leaking a 500`() {
        whenever(accountImportService.import(eq(userId), any()))
            .thenThrow(DataIntegrityViolationException("uq_users_email_hash"))

        mockMvc.perform(
            post("/api/v1/account/import")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalPayload)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("Import conflicts with existing data and could not be completed."))
    }
}
