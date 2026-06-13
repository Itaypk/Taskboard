package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Resolves board members' display names for the members endpoint and assignee chips.
 *
 * This is a **deliberate, documented exception** to "only decrypt a user's data in their own request
 * context" (docs/BOARD-SHARING-PHASE2.md, Decision 4): the server holds the KEK and already decrypts
 * in-request, and a co-member needs *some* human label. The surface is kept minimal — per member the
 * first available of display name → Telegram first name → Telegram username → **masked** email →
 * "Member". A co-member's full email is never exposed; the consent/accept copy states that your name
 * becomes visible to board members.
 */
@Service
class MemberDisplayNameResolver(
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val userCrypto: UserCryptoService,
) {

    /** Resolves a display label for each of [userIds]. Always returns an entry per id (≥ "Member"). */
    @Transactional(readOnly = true)
    fun resolve(userIds: Collection<UUID>): Map<UUID, String> {
        val ids = userIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        val users = userRepository.findAllById(ids).associateBy { it.id }
        val settings = userSettingsRepository.findAllById(ids).associateBy { it.userId }
        return ids.associateWith { id ->
            val displayName = settings[id]?.displayName?.let { userCrypto.decrypt(id, it) }?.takeIf { it.isNotBlank() }
            val user = users[id]
            val firstName = user?.telegramFirstName?.let { userCrypto.decrypt(id, it) }?.takeIf { it.isNotBlank() }
            val username = user?.telegramUsername?.takeIf { it.isNotBlank() }
            val maskedEmail = user?.email?.let { userCrypto.decrypt(id, it) }?.let(::maskEmail)
            displayName ?: firstName ?: username ?: maskedEmail ?: "Member"
        }
    }

    /** `itaypk@gmail.com` -> `it***@gmail.com`; never returns the full local part. */
    private fun maskEmail(email: String): String? {
        val at = email.indexOf('@')
        if (at <= 0) return null
        val local = email.substring(0, at)
        val domain = email.substring(at)
        val keep = if (local.length <= 2) 1 else 2
        return local.take(keep) + "***" + domain
    }
}
