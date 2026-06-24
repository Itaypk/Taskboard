package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.FeedbackService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(FeedbackController::class)
@Import(SecurityConfiguration::class)
class FeedbackControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var feedbackService: FeedbackService

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    @Test
    fun `unauthenticated submission returns 401`() {
        mockMvc.perform(
            post("/api/v1/feedback")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"hi"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `missing CSRF token returns 403`() {
        mockMvc.perform(
            post("/api/v1/feedback")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"hi"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `valid submission returns 204 and delegates to the service`() {
        mockMvc.perform(
            post("/api/v1/feedback")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"Love the app","replyEmail":"me@example.com"}"""),
        ).andExpect(status().isNoContent)

        verify(feedbackService).submit(eq(userId), eq("Love the app"), eq("me@example.com"))
    }

    @Test
    fun `submission without reply email passes null`() {
        mockMvc.perform(
            post("/api/v1/feedback")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"Just a note"}"""),
        ).andExpect(status().isNoContent)

        verify(feedbackService).submit(eq(userId), eq("Just a note"), isNull())
    }

    @Test
    fun `blank message is rejected with 400`() {
        mockMvc.perform(
            post("/api/v1/feedback")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"   "}"""),
        ).andExpect(status().isBadRequest)

        verify(feedbackService, never()).submit(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())
    }
}
