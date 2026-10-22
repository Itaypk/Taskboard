package dev.itayp.tasker.service

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.crypto.InboundMailTokenHasher
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.InboundMailAddressEntity
import dev.itayp.tasker.repository.InboundMailAddressRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A user's inbound address, ready to show. */
data class InboundMailAddress(
    val address: String,
    val createdAt: Instant,
)

/**
 * Personal inbound email addresses: `tasks.<token>@<inbound domain>` (GitHub issue #288).
 *
 * The address is a capability: whoever knows it can add tasks for the user, so the token is the
 * whole of the sender authentication (the `From:` header is trivially forged). It is bound to be
 * seen by others sooner or later (reply-all, CC, a quoted thread), which is why it can be rotated
 * or turned off at any time. The `tasks.` prefix is cosmetic, so the address is recognizable in a
 * contact list; lookup ignores it.
 *
 * Never log an address or a token: log the user id only.
 */
@Service
class InboundMailAddressService(
    private val repository: InboundMailAddressRepository,
    private val userCryptoService: UserCryptoService,
    private val tokenHasher: InboundMailTokenHasher,
    private val properties: InboundMailProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(InboundMailAddressService::class.java)
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun getAddress(userId: UUID): InboundMailAddress? =
        repository.findById(userId).orElse(null)?.toAddress(userId)

    /** Issues a new address, replacing the current one if there is one: the old address stops working. */
    @Transactional
    fun rotate(userId: UUID): InboundMailAddress {
        val existing = repository.findById(userId).orElse(null)
        val token = generateToken()
        val entity = (existing ?: InboundMailAddressEntity().apply { this.userId = userId }).apply {
            tokenHmac = tokenHasher.hash(token)
            this.token = userCryptoService.encrypt(userId, token)
            createdAt = clock.instant()
        }
        repository.save(entity)
        log.info("{} inbound mail address for user {}", if (existing == null) "Created" else "Rotated", userId)
        return entity.toAddress(userId)
    }

    /** Turns the address off. Returns false when the user had none. */
    @Transactional
    fun revoke(userId: UUID): Boolean {
        if (!repository.existsById(userId)) return false
        repository.deleteById(userId)
        log.info("Revoked inbound mail address for user {}", userId)
        return true
    }

    /**
     * The user a delivery is for, from its envelope recipient, or null when it isn't a live
     * address of ours: wrong domain, wrong shape, or a token that was rotated or never existed.
     */
    @Transactional(readOnly = true)
    fun resolveRecipient(recipient: String): UUID? {
        val normalized = recipient.trim().lowercase()
        val at = normalized.lastIndexOf('@')
        if (at <= 0 || normalized.substring(at + 1) != properties.normalizedDomain) return null
        val token = LOCAL_PART.matchEntire(normalized.substring(0, at))?.groupValues?.get(1) ?: return null
        return repository.findByTokenHmac(tokenHasher.hash(token))?.userId
    }

    private fun InboundMailAddressEntity.toAddress(userId: UUID): InboundMailAddress {
        val plainToken = checkNotNull(userCryptoService.decrypt(userId, token)) { "inbound mail token missing" }
        return InboundMailAddress(
            address = "$LOCAL_PART_PREFIX.$plainToken@${properties.normalizedDomain}",
            createdAt = checkNotNull(createdAt),
        )
    }

    private fun generateToken(): String =
        String(CharArray(TOKEN_LENGTH) { BASE32_ALPHABET[random.nextInt(BASE32_ALPHABET.length)] })

    companion object {
        const val LOCAL_PART_PREFIX = "tasks"

        /** 10 characters of base32 = 50 bits. Long enough not to be guessed online, short enough to read. */
        const val TOKEN_LENGTH = 10

        /** RFC 4648 base32, lowercase: some MTAs mangle case in local parts, and 0/1/8/9 are left out. */
        private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        /**
         * `<anything cosmetic>.<token>`. The prefix is accepted in any shape the MTA lets through,
         * so a future change to it (or a user-chosen one) doesn't break addresses already handed out.
         */
        private val LOCAL_PART = Regex("^[a-z0-9-]{1,32}\\.([a-z2-7]{$TOKEN_LENGTH})$")
    }
}
