package dev.itayp.tasker.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

/**
 * Enforces an absolute maximum lifetime on authenticated sessions, independent of activity.
 * Spring Session's `timeout` is a sliding (idle) timeout — an active attacker holding a
 * stolen cookie can keep it alive indefinitely. This filter invalidates any session whose
 * authedAt anchor (set by [SessionAuthenticator]) is older than [maxLifetime].
 *
 * Note the anchor is re-stamped on every login, so the cap is "N days since last sign-in",
 * not since the account was created.
 *
 * Registered as an explicit `@Bean` (not `@Component`) so that `@WebMvcTest` slices, which
 * auto-scan Filter beans, don't try to instantiate it without a Clock available — and wrapped
 * in a `FilterRegistrationBean` so it runs *before* the Spring Security chain. Ordering is
 * load-bearing: at the default `LOWEST_PRECEDENCE` the security chain had already authorized
 * the request by the time this ran, so the request that tripped the cap was still served
 * authenticated and expiry only bit on the next one.
 */
class AbsoluteSessionLifetimeFilter(
    private val clock: Clock,
    private val maxLifetime: Duration,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val session = request.getSession(false)
        if (session != null) {
            val authedAt = session.getAttribute(SessionAuthenticator.AUTHED_AT_ATTRIBUTE) as? Long
            if (authedAt != null) {
                val ageSeconds = clock.instant().epochSecond - authedAt
                if (ageSeconds > maxLifetime.seconds) {
                    session.invalidate()
                    SecurityContextHolder.clearContext()
                    // Mirror AccountController.deleteAccount's teardown: without this the browser
                    // keeps presenting a cookie that can never authenticate again.
                    response.addHeader("Set-Cookie", "SESSION=; Max-Age=0; Path=/; HttpOnly")
                    response.addHeader("Set-Cookie", "XSRF-TOKEN=; Max-Age=0; Path=/")
                }
            }
        }
        chain.doFilter(request, response)
    }
}
