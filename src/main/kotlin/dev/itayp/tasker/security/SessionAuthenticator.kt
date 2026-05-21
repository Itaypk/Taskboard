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
import java.time.Clock

/**
 * Creates an authenticated Spring Security session for a principal that has already
 * been verified out-of-band (Telegram HMAC, dev-login, demo-login). `saveContext` is
 * mandatory in Spring Security 6+ — without it the session cookie is not issued.
 *
 * Rotates the session id on login (session-fixation defense). Spring Security's
 * `sessionFixation { newSession() }` strategy only fires through the standard
 * AuthenticationFilter chain, which these custom auth flows bypass — so we rotate
 * explicitly here.
 */
@Component
class SessionAuthenticator(private val clock: Clock) {

    private val holderStrategy = SecurityContextHolder.getContextHolderStrategy()
    private val repository: SecurityContextRepository = HttpSessionSecurityContextRepository()

    fun authenticate(principal: TaskerPrincipal, request: HttpServletRequest, response: HttpServletResponse) {
        val auth: Authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        request.getSession(false)?.let { request.changeSessionId() }
        val ctx = holderStrategy.createEmptyContext().apply { authentication = auth }
        holderStrategy.context = ctx
        repository.saveContext(ctx, request, response)
        // After saveContext the session is guaranteed to exist. Stamp the absolute-lifetime
        // anchor so AbsoluteSessionLifetimeFilter can enforce a hard cap regardless of activity.
        request.getSession(false)?.setAttribute(AUTHED_AT_ATTRIBUTE, clock.instant().epochSecond)
    }

    companion object {
        const val AUTHED_AT_ATTRIBUTE = "tasker.authedAtEpochSeconds"
    }
}
