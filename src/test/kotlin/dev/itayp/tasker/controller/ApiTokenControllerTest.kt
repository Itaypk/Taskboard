package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.jpa.ApiTokenEntity
import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.ApiTokenService
import dev.itayp.tasker.service.CreatedApiToken
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

@WebMvcTest(ApiTokenController::class)
@Import(SecurityConfiguration::class)
class ApiTokenControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var apiTokenService: ApiTokenService

    private val userId = UUID.randomUUID()
    private val auth = authentication(
        UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
    )

    private fun created(expiresAt: Instant?) = CreatedApiToken(
        token = ApiTokenEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@ApiTokenControllerTest.userId
            name = "Scripts"
            prefix = "blf_a1b2c3d4"
            scope = ApiTokenScope.WRITE
            createdAt = Instant.parse("2026-10-05T00:00:00Z")
            this.expiresAt = expiresAt
        },
        plaintext = "blf_secret",
    )

    private fun create(body: String) =
        mockMvc.perform(
            post("/api/v1/api-tokens").with(auth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )

    @Test
    fun `passes the requested lifetime through and reports the expiry`() {
        val expiresAt = Instant.parse("2027-01-03T00:00:00Z")
        whenever(apiTokenService.createToken(eq(userId), eq("Scripts"), eq("write"), eq(Duration.ofDays(90))))
            .thenReturn(created(expiresAt))

        create("""{"name":"Scripts","scope":"write","expiresInDays":90}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.apiToken.expiresAt").value(expiresAt.toString()))
    }

    @Test
    fun `omitting the lifetime mints a non-expiring token`() {
        whenever(apiTokenService.createToken(eq(userId), eq("Scripts"), eq("write"), isNull()))
            .thenReturn(created(null))

        create("""{"name":"Scripts","scope":"write"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.apiToken.expiresAt").doesNotExist())
    }

    @Test
    fun `rejects a lifetime outside one day to a year`() {
        create("""{"name":"Scripts","scope":"write","expiresInDays":0}""").andExpect(status().isBadRequest)
        create("""{"name":"Scripts","scope":"write","expiresInDays":366}""").andExpect(status().isBadRequest)

        verify(apiTokenService, never()).createToken(any(), any(), any(), anyOrNull())
    }
}
