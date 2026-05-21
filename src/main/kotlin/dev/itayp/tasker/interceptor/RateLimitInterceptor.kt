package dev.itayp.tasker.interceptor

import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.security.TaskerPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

class RateLimitInterceptor(
    /** Applied per authenticated user ID on all API endpoints. */
    private val apiLimiter: RateLimiter,
    /** Applied per client IP on the demo-login endpoint, before a session exists. */
    private val demoLoginLimiter: RateLimiter,
    /** Applied per client IP on the telegram-login endpoint, before a session exists. */
    private val telegramLoginLimiter: RateLimiter,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        when (request.servletPath) {
            DEMO_LOGIN_PATH -> {
                if (!demoLoginLimiter.tryConsume(request.clientIp())) return reject(response)
                return true
            }
            TELEGRAM_LOGIN_PATH -> {
                if (!telegramLoginLimiter.tryConsume(request.clientIp())) return reject(response)
                return true
            }
        }

        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal is TaskerPrincipal) {
            if (!apiLimiter.tryConsume(principal.userId.toString())) return reject(response)
        }

        return true
    }

    private fun reject(response: HttpServletResponse): Boolean {
        response.status = 429
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"Too many requests. Please slow down."}""")
        return false
    }

    companion object {
        private const val DEMO_LOGIN_PATH = "/api/auth/demo-login"
        private const val TELEGRAM_LOGIN_PATH = "/api/auth/telegram"
    }
}

// Nginx in front of this app sets `X-Forwarded-For: $proxy_add_x_forwarded_for`, which
// APPENDS the immediate client IP to whatever XFF the client supplied. Taking the *last*
// entry therefore yields the address Nginx saw — which is what we want for per-IP
// throttling. Taking the first entry (a previous, common mistake here) would trust the
// attacker-supplied value and make the rate limit trivially bypassable.
private fun HttpServletRequest.clientIp(): String =
    getHeader("X-Forwarded-For")
        ?.split(',')
        ?.lastOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: remoteAddr
