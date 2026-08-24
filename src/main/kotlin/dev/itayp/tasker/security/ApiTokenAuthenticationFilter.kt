package dev.itayp.tasker.security

import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.service.ApiTokenService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates `Authorization: Bearer <token>` against the `api_token` table for the external API.
 *
 * Installs the very same [TaskerPrincipal] the session flows use, so every downstream
 * `BoardMembershipService.requireMember` check and the per-user rate limiter keep working
 * unchanged — a token-authenticated request is indistinguishable from a session one below the
 * security layer. What differs is the granted authorities: every valid token gets `EXTERNAL_READ`,
 * and only a write-scoped one additionally gets `EXTERNAL_WRITE`.
 *
 * An absent or unusable token leaves the context empty; the chain's entry point turns that into
 * a 401. We never distinguish unknown / revoked / expired to the caller.
 *
 * Deliberately **not** a `@Component`: Spring Boot auto-registers a `Filter` bean in the servlet
 * chain for every request, and this one must run only inside the external security chain.
 * [dev.itayp.tasker.config.SecurityConfiguration] constructs it there instead.
 */
class ApiTokenAuthenticationFilter(
    private val apiTokenService: ApiTokenService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        var authenticated = false
        val raw = bearerToken(request)
        if (raw != null) {
            val resolved = apiTokenService.resolve(raw)
            if (resolved != null) {
                val authorities = buildList {
                    add(SimpleGrantedAuthority("ROLE_USER"))
                    add(SimpleGrantedAuthority(EXTERNAL_READ))
                    if (resolved.scope == ApiTokenScope.WRITE) add(SimpleGrantedAuthority(EXTERNAL_WRITE))
                }
                val authentication = UsernamePasswordAuthenticationToken(
                    TaskerPrincipal(resolved.userId), null, authorities,
                )
                val context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext()
                context.authentication = authentication
                SecurityContextHolder.getContextHolderStrategy().context = context
                authenticated = true
            }
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            // Clear only what we set. Nothing persists the context on a stateless chain, so a
            // token-authenticated principal would otherwise ride a pooled request thread; a
            // context set by anything else upstream is that filter's to unwind.
            if (authenticated) SecurityContextHolder.getContextHolderStrategy().clearContext()
        }
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "

        const val EXTERNAL_READ = "EXTERNAL_READ"
        const val EXTERNAL_WRITE = "EXTERNAL_WRITE"
    }
}
