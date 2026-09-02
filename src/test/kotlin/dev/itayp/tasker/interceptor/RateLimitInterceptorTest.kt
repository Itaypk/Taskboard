package dev.itayp.tasker.interceptor

import dev.itayp.tasker.ratelimit.InMemoryRateLimiter
import dev.itayp.tasker.ratelimit.RateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Covers the pre-session, per-IP branches. These run before any authentication, so they are the
 * only thing standing between a stranger and the endpoints that mint accounts or send mail.
 */
class RateLimitInterceptorTest {

    private fun interceptor(
        emailLoginLimit: Int = 2,
        demoLoginLimit: Int = 2,
    ): RateLimitInterceptor {
        val generous = RateLimiter { true }
        return RateLimitInterceptor(
            apiLimiter = generous,
            demoLoginLimiter = InMemoryRateLimiter(limit = demoLoginLimit, windowMillis = 60_000),
            telegramLoginLimiter = generous,
            emailLoginLimiter = InMemoryRateLimiter(limit = emailLoginLimit, windowMillis = 60_000),
            externalApiLimiter = generous,
        )
    }

    private fun call(
        subject: RateLimitInterceptor,
        path: String,
        ip: String,
        method: String = "POST",
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path)
        // The controllers are mapped at the servlet root, so servletPath is the full path.
        request.servletPath = path
        request.remoteAddr = ip
        val response = MockHttpServletResponse()
        subject.preHandle(request, response, Any())
        return response
    }

    @Test
    fun `throttles magic-link sends per client IP`() {
        val subject = interceptor(emailLoginLimit = 2)

        assertThat(call(subject, "/api/auth/email", "10.0.0.1").status).isEqualTo(200)
        assertThat(call(subject, "/api/auth/email", "10.0.0.1").status).isEqualTo(200)

        // The third send from this host is refused even though each targeted a different address,
        // which the per-address limit in EmailLoginService would happily have allowed.
        assertThat(call(subject, "/api/auth/email", "10.0.0.1").status).isEqualTo(429)
    }

    @Test
    fun `magic-link budget is per IP, so another host is unaffected`() {
        val subject = interceptor(emailLoginLimit = 2)
        repeat(3) { call(subject, "/api/auth/email", "10.0.0.1") }

        assertThat(call(subject, "/api/auth/email", "10.0.0.2").status).isEqualTo(200)
    }

    @Test
    fun `completing a login does not spend the sender's budget`() {
        val subject = interceptor(emailLoginLimit = 2)
        repeat(3) { call(subject, "/api/auth/email", "10.0.0.1") }

        // The recipient's half of the flow lives under the same prefix but must stay reachable.
        assertThat(call(subject, "/api/auth/email/callback", "10.0.0.1").status).isEqualTo(200)
        assertThat(call(subject, "/api/auth/email/precheck", "10.0.0.1", method = "GET").status).isEqualTo(200)
    }

    @Test
    fun `prefers the proxy-appended forwarded-for entry over a client-supplied one`() {
        val subject = interceptor(emailLoginLimit = 2)

        // Nginx appends the address it saw, so the LAST entry is the trustworthy one. A client
        // rotating the first entry must not win itself a fresh budget.
        repeat(3) { attempt ->
            val request = MockHttpServletRequest("POST", "/api/auth/email")
            request.servletPath = "/api/auth/email"
            request.addHeader("X-Forwarded-For", "1.2.3.$attempt, 10.0.0.1")
            request.remoteAddr = "127.0.0.1"
            val response = MockHttpServletResponse()
            subject.preHandle(request, response, Any())
            if (attempt == 2) assertThat(response.status).isEqualTo(429)
        }
    }

    @Test
    fun `throttles demo-login per client IP`() {
        val subject = interceptor(demoLoginLimit = 2)

        assertThat(call(subject, "/api/auth/demo-login", "10.0.0.1").status).isEqualTo(200)
        assertThat(call(subject, "/api/auth/demo-login", "10.0.0.1").status).isEqualTo(200)
        assertThat(call(subject, "/api/auth/demo-login", "10.0.0.1").status).isEqualTo(429)
        assertThat(call(subject, "/api/auth/demo-login", "10.0.0.2").status).isEqualTo(200)
    }
}
