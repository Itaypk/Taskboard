package dev.itayp.tasker.security

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AbsoluteSessionLifetimeFilterTest {

    private val now = Instant.parse("2026-08-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val maxLifetime = Duration.ofDays(365)
    private val filter = AbsoluteSessionLifetimeFilter(clock, maxLifetime)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `session inside the window passes through untouched`() {
        val session = sessionAuthedAt(now.minus(Duration.ofDays(364)))
        val request = MockHttpServletRequest().apply { setSession(session) }
        val response = MockHttpServletResponse()
        authenticate()

        filter.doFilter(request, response, mock<FilterChain>())

        assertThat(session.isInvalid).isFalse()
        assertThat(SecurityContextHolder.getContext().authentication).isNotNull()
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
    }

    @Test
    fun `session past the window is invalidated, context cleared and cookies expired`() {
        val session = sessionAuthedAt(now.minus(Duration.ofDays(366)))
        val request = MockHttpServletRequest().apply { setSession(session) }
        val response = MockHttpServletResponse()
        authenticate()

        filter.doFilter(request, response, mock<FilterChain>())

        assertThat(session.isInvalid).isTrue()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        // Without the teardown the browser keeps presenting a cookie that can never authenticate.
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
            .anyMatch { it.startsWith("SESSION=;") && it.contains("Max-Age=0") }
            .anyMatch { it.startsWith("XSRF-TOKEN=;") && it.contains("Max-Age=0") }
    }

    @Test
    fun `the chain is always invoked, expired or not`() {
        val chain = mock<FilterChain>()
        val request = MockHttpServletRequest().apply { setSession(sessionAuthedAt(now.minus(Duration.ofDays(400)))) }

        filter.doFilter(request, MockHttpServletResponse(), chain)

        org.mockito.kotlin.verify(chain).doFilter(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `a session with no authedAt anchor is left alone`() {
        // Sessions created before the anchor existed, and unauthenticated ones, must not be
        // expired by age they never recorded.
        val session = MockHttpSession()
        val request = MockHttpServletRequest().apply { setSession(session) }

        filter.doFilter(request, MockHttpServletResponse(), mock<FilterChain>())

        assertThat(session.isInvalid).isFalse()
    }

    @Test
    fun `a request with no session at all is a no-op`() {
        val response = MockHttpServletResponse()

        filter.doFilter(MockHttpServletRequest(), response, mock<FilterChain>())

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
    }

    private fun sessionAuthedAt(instant: Instant) = MockHttpSession().apply {
        setAttribute(SessionAuthenticator.AUTHED_AT_ATTRIBUTE, instant.epochSecond)
    }

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(TaskerPrincipal(UUID.randomUUID()), null, emptyList())
    }
}
