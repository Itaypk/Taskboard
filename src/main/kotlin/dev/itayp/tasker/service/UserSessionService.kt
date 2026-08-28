package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.response.ActiveSessionResponse
import dev.itayp.tasker.security.SessionAuthenticator
import org.slf4j.LoggerFactory
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Reads and revokes the signed-in user's own sessions, backing the "active sessions" section of
 * Settings. This is the user-facing counterweight to the long absolute session lifetime
 * (`tasker.session.max-lifetime`): without it, a user who suspects a stolen cookie has no way to
 * do anything about it short of deleting the account.
 *
 * Lookup goes through Spring Session's principal-name index — `SPRING_SESSION.PRINCIPAL_NAME`
 * plus its index already exist in changeset 001, and [SessionAuthenticator] stamps the index
 * attribute at login, so no schema change was needed.
 *
 * Only "revoke every session but this one" is offered. Per-session revocation would mean handing
 * the browser a stable per-session identifier, which is worth avoiding for no real gain here.
 */
@Service
class UserSessionService(
    private val sessionRepository: FindByIndexNameSessionRepository<out Session>,
    private val userCrypto: UserCryptoService,
) {
    private val log = LoggerFactory.getLogger(UserSessionService::class.java)

    fun list(userId: UUID, currentSessionId: String?): List<ActiveSessionResponse> =
        findForUser(userId).values
            // Current device first, then most recently active — the order a user scans for
            // "which one of these isn't me?". Sorted on the Instant, not the rendered string:
            // Instant.toString() drops trailing zeros, so lexicographic order is not chronological.
            .sortedWith(
                compareByDescending<Session> { it.id == currentSessionId }
                    .thenByDescending { it.lastAccessedTime },
            )
            .map { it.toResponse(userId, isCurrent = it.id == currentSessionId) }

    /** Deletes every session belonging to [userId] except [currentSessionId]. Returns how many went. */
    fun revokeOthers(userId: UUID, currentSessionId: String?): Int {
        val doomed = findForUser(userId).keys.filter { it != currentSessionId }
        doomed.forEach { sessionRepository.deleteById(it) }
        log.info("Revoked {} other session(s) for user {}", doomed.size, userId)
        return doomed.size
    }

    private fun findForUser(userId: UUID): Map<String, out Session> =
        sessionRepository.findByPrincipalName(userId.toString())

    private fun Session.toResponse(userId: UUID, isCurrent: Boolean): ActiveSessionResponse {
        // Sessions created before session metadata was recorded carry none of these attributes,
        // and must still list rather than blow up the whole screen.
        val authedAt = getAttribute<Long>(SessionAuthenticator.AUTHED_AT_ATTRIBUTE)
        val ip = runCatching {
            userCrypto.decrypt(userId, getAttribute(SessionAuthenticator.IP_ATTRIBUTE))
        }.getOrNull()

        return ActiveSessionResponse(
            current = isCurrent,
            device = getAttribute<String>(SessionAuthenticator.DEVICE_ATTRIBUTE),
            ipAddress = ip,
            signedInAt = (authedAt?.let(Instant::ofEpochSecond) ?: creationTime).toString(),
            lastActiveAt = lastAccessedTime.toString(),
        )
    }
}
