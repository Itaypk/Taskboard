package dev.itayp.tasker.calendar.source

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.controller.ApiExceptionHandler
import dev.itayp.tasker.security.TaskerPrincipal
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(CalendarSourceController::class)
@Import(SecurityConfiguration::class, ApiExceptionHandler::class)
class CalendarSourceControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var service: CalendarSourceService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val auth = authentication(
        UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
    )

    private val view = CalendarSourceView(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
        label = "Work",
        host = "outlook.office365.com",
        detail = CalendarSourceDetail.TITLES,
        createdAt = Instant.parse("2026-11-26T10:00:00Z"),
        lastAttemptAt = Instant.parse("2026-11-26T10:00:00Z"),
        lastSuccessAt = Instant.parse("2026-11-26T10:00:00Z"),
        lastError = null,
        lastEventCount = 23,
    )

    @Test
    fun `lists calendars without their addresses`() {
        whenever(service.list(userId)).thenReturn(listOf(view))

        mockMvc.perform(get("/api/v1/calendar-sources").with(auth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].host").value("outlook.office365.com"))
            .andExpect(jsonPath("$[0].lastEventCount").value(23))
            .andExpect(jsonPath("$[0].url").doesNotExist())
    }

    @Test
    fun `adds a calendar`() {
        whenever(service.add(eq(userId), eq("https://example.com/a.ics"), eq("Work"), eq(CalendarSourceDetail.BUSY))).thenReturn(view)

        mockMvc.perform(
            post("/api/v1/calendar-sources").with(auth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://example.com/a.ics","label":"Work","detail":"BUSY"}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.label").value("Work"))
    }

    @Test
    fun `a refused address answers 400 with its code, and the rate limit 429`() {
        whenever(service.add(any(), any(), anyOrNull(), any()))
            .thenThrow(CalendarSourceException("NOT_A_CALENDAR"))
            .thenThrow(CalendarSourceException(CalendarSourceException.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS))
        val request = post("/api/v1/calendar-sources").with(auth).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"url":"https://example.com/a.ics"}""")

        mockMvc.perform(request).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("NOT_A_CALENDAR"))
        mockMvc.perform(request).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `needs a session and a CSRF token`() {
        mockMvc.perform(get("/api/v1/calendar-sources")).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/v1/calendar-sources/${view.id}").with(auth)).andExpect(status().isForbidden)
        verify(service, never()).delete(any(), any())
    }

    @Test
    fun `an unknown calendar is 404`() {
        whenever(service.refresh(userId, view.id)).thenThrow(CalendarSourceNotFoundException())

        mockMvc.perform(post("/api/v1/calendar-sources/${view.id}/refresh").with(auth).with(csrf()))
            .andExpect(status().isNotFound)
    }
}
