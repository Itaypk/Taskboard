package dev.itayp.tasker.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

// Enforces an absolute maximum lifetime on authenticated sessions, independent of activity.
// Spring Session's `timeout` is a sliding (idle) timeout — an active attacker holding a
// stolen cookie can keep it alive indefinitely. This filter invalidates any session whose
// authedAt anchor (set by SessionAuthenticator) is older than MAX_SESSION_LIFETIME.
// Registered as an explicit @Bean (not @Component) so that @WebMvcTest slices, which
// auto-scan Filter beans, don't try to instantiate it without a Clock available.
class AbsoluteSessionLifetimeFilter(private val clock: Clock) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val session = request.getSession(false)
        if (session != null) {
            val authedAt = session.getAttribute(SessionAuthenticator.AUTHED_AT_ATTRIBUTE) as? Long
            if (authedAt != null) {
                val ageSeconds = clock.instant().epochSecond - authedAt
                if (ageSeconds > MAX_SESSION_LIFETIME.seconds) {
                    session.invalidate()
                    SecurityContextHolder.clearContext()
                }
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        val MAX_SESSION_LIFETIME: Duration = Duration.ofDays(90)
    }
}
