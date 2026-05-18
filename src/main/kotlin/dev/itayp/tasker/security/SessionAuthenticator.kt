package dev.itayp.tasker.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Component

/**
 * Creates an authenticated Spring Security session for a principal that has already
 * been verified out-of-band (Telegram HMAC or dev-login). `saveContext` is mandatory
 * in Spring Security 6+ — without it the session cookie is not issued.
 */
@Component
class SessionAuthenticator {

    private val holderStrategy = SecurityContextHolder.getContextHolderStrategy()
    private val repository: SecurityContextRepository = HttpSessionSecurityContextRepository()

    fun authenticate(principal: TaskerPrincipal, request: HttpServletRequest, response: HttpServletResponse) {
        val auth: Authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        val ctx = holderStrategy.createEmptyContext().apply { authentication = auth }
        holderStrategy.context = ctx
        repository.saveContext(ctx, request, response)
    }
}
