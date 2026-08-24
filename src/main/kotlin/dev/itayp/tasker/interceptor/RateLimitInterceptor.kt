package dev.itayp.tasker.interceptor

import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.util.clientIp
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
    /** Applied per token-authenticated user on the external API, which has its own tighter budget. */
    private val externalApiLimiter: RateLimiter,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val servletPath = request.servletPath
        if (servletPath == DEMO_LOGIN_PATH) {
            if (!demoLoginLimiter.tryConsume(request.clientIp())) return reject(response)
            return true
        }
        // Prefix match: the real endpoints are /api/auth/telegram/{start,callback,link/...},
        // never the bare prefix, so an exact-equality check would never fire.
        if (servletPath.startsWith(TELEGRAM_LOGIN_PREFIX)) {
            if (!telegramLoginLimiter.tryConsume(request.clientIp())) return reject(response)
            return true
        }

        val principal = SecurityContextHolder.getContext().authentication?.principal

        // External API gets its own budget rather than sharing the SPA's, so a chatty script can't
        // starve the owner's browser session (and vice versa). Keyed per user, like the main
        // limiter — the token filter installs a TaskerPrincipal exactly so this keeps working.
        if (servletPath.startsWith(EXTERNAL_API_PREFIX)) {
            if (principal is TaskerPrincipal && !externalApiLimiter.tryConsume(principal.userId.toString())) {
                return reject(response)
            }
            return true
        }

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
        private const val TELEGRAM_LOGIN_PREFIX = "/api/auth/telegram"
        private const val EXTERNAL_API_PREFIX = "/api/external/"
    }
}
