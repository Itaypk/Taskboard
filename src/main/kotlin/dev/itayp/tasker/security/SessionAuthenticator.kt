package dev.itayp.tasker.security

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.util.clientIp
import dev.itayp.tasker.util.deviceLabel
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.session.FindByIndexNameSessionRepository
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
 *
 * Also, the single place session metadata is stamped, which is what makes the
 * active-sessions list in Settings possible: every login path funnels through here.
 */
@Component
class SessionAuthenticator(
    private val clock: Clock,
    private val userCrypto: UserCryptoService,
) {
    private val log = LoggerFactory.getLogger(SessionAuthenticator::class.java)

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
        // After saveContext the session is guaranteed to exist.
        request.getSession(false)?.let { stampMetadata(it, principal, request) }
    }

    private fun stampMetadata(session: HttpSession, principal: TaskerPrincipal, request: HttpServletRequest) {
        // The absolute-lifetime anchor, so AbsoluteSessionLifetimeFilter can enforce a hard cap
        // regardless of activity.
        session.setAttribute(AUTHED_AT_ATTRIBUTE, clock.instant().epochSecond)

        // JdbcIndexedSessionRepository would most likely derive PRINCIPAL_NAME from the security
        // context anyway (TaskerPrincipal.toString() is the user UUID), but setting the index
        // attribute explicitly is the documented contract — and findByPrincipalName is what the
        // active-sessions list and "sign out everywhere else" are built on.
        session.setAttribute(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
            principal.userId.toString(),
        )

        // Descriptive only. A failure here must never cost the user their login, so it is
        // best-effort: the session simply lists without a device or IP.
        runCatching {
            session.setAttribute(DEVICE_ATTRIBUTE, deviceLabel(request.getHeader("User-Agent")))
            session.setAttribute(IP_ATTRIBUTE, userCrypto.encrypt(principal.userId, request.clientIp()))
        }.onFailure { log.warn("Could not stamp session metadata for user {}", principal.userId, it) }
    }

    companion object {
        const val AUTHED_AT_ATTRIBUTE = "tasker.authedAtEpochSeconds"

        /** Coarse "Chrome on macOS" label. Never the raw User-Agent. */
        const val DEVICE_ATTRIBUTE = "tasker.session.device"

        /** Client IP, encrypted under the user's DEK — it is personal data at rest. */
        const val IP_ATTRIBUTE = "tasker.session.ip"
    }
}
