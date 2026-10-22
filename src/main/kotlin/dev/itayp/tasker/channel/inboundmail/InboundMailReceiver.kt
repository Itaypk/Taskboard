package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.InboundMailEntity
import dev.itayp.tasker.jpa.InboundMailStatus
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.repository.InboundMailRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.EmailHasher
import dev.itayp.tasker.service.InboundMailAddressService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** What became of a delivery. Every outcome but [QUEUED] means the message was dropped. */
enum class ReceiveOutcome {
    QUEUED,

    /** Already received: the MTA retried a delivery it wasn't sure we got. */
    DUPLICATE,

    /** Not one of our live addresses: never existed, rotated, or turned off. */
    UNKNOWN_RECIPIENT,

    /** Mail for `postmaster@` (RFC 5321 requires accepting it). Logged for the operator, never captured. */
    POSTMASTER,

    /** Empty envelope sender: a delivery-status notification. Never captured, never answered. */
    BOUNCE,

    /** `Auto-Submitted:` set: an auto-reply or a robot. Never captured, so two robots can't loop. */
    AUTO_SUBMITTED,
    RATE_LIMITED,
    MALFORMED,
}

/**
 * The receiving half of inbound email: from the MTA's delivery to a row in the `inbound_mail`
 * queue. Deliberately cheap (no model call), so the MTA gets its answer in milliseconds;
 * [InboundMailProcessor] does the capture.
 *
 * Identity comes from the address token alone. The "verified" flag only says whether the message
 * provably came from the account's own email address (DMARC pass, per our MTA's own
 * `Authentication-Results`, for a `From:` matching the account's verified email): a manual forward
 * is verified, an auto-forwarding rule or a stranger who learned the address isn't. In v1 both are
 * captured; the confirmation says which it was.
 *
 * Nothing here logs an address, a subject or a body.
 */
@Service
class InboundMailReceiver(
    private val addressService: InboundMailAddressService,
    private val parser: InboundMailParser,
    private val repository: InboundMailRepository,
    private val userRepository: UserRepository,
    private val userCryptoService: UserCryptoService,
    private val objectMapper: ObjectMapper,
    @Qualifier("inboundMailRateLimiter") private val rateLimiter: RateLimiter,
    private val properties: InboundMailProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(InboundMailReceiver::class.java)

    fun receive(envelopeFrom: String?, envelopeTo: String, raw: ByteArray): ReceiveOutcome =
        decide(envelopeFrom, envelopeTo, raw).also { outcome ->
            meterRegistry.counter("tasker.inbound_mail.received", "outcome", outcome.name.lowercase()).increment()
        }

    private fun decide(envelopeFrom: String?, envelopeTo: String, raw: ByteArray): ReceiveOutcome {
        if (envelopeTo.trim().lowercase() == "postmaster@${properties.normalizedDomain}") {
            log.warn("Mail for postmaster@ received ({} bytes); not captured. Check the MTA's spool or logs.", raw.size)
            return ReceiveOutcome.POSTMASTER
        }
        val userId = addressService.resolveRecipient(envelopeTo) ?: run {
            log.info("Inbound mail for an unknown or revoked address dropped")
            return ReceiveOutcome.UNKNOWN_RECIPIENT
        }
        if (envelopeFrom.isNullOrBlank() || envelopeFrom.trim() == "<>") {
            log.info("Bounce to the inbound address of user {} dropped", userId)
            return ReceiveOutcome.BOUNCE
        }
        val mail = try {
            parser.parse(raw)
        } catch (e: InboundMailParseException) {
            log.info("Unparseable inbound mail for user {} dropped: {}", userId, e.message)
            return ReceiveOutcome.MALFORMED
        }
        if (mail.autoSubmitted) {
            log.info("Auto-submitted inbound mail for user {} dropped", userId)
            return ReceiveOutcome.AUTO_SUBMITTED
        }
        val dedupKey = dedupKey(userId, mail.messageId, raw)
        if (repository.existsByDedupKey(dedupKey)) {
            log.debug("Duplicate inbound mail for user {} ignored", userId)
            return ReceiveOutcome.DUPLICATE
        }
        if (!rateLimiter.tryConsume(userId.toString())) {
            log.warn("Inbound mail rate limit reached for user {}; message dropped", userId)
            return ReceiveOutcome.RATE_LIMITED
        }

        val payload = InboundMailPayload(
            subject = mail.subject,
            fromAddress = mail.fromAddress,
            fromName = mail.fromName,
            body = mail.body,
            attachmentCount = mail.attachmentCount,
            verified = isVerified(userId, mail),
        )
        val now = clock.instant()
        val entity = InboundMailEntity().apply {
            id = UUID.randomUUID()
            this.userId = userId
            this.dedupKey = dedupKey
            nextAttemptAt = now
            this.payload = userCryptoService.encrypt(userId, objectMapper.writeValueAsString(payload))
            receivedAt = now
        }
        try {
            repository.saveAndFlush(entity)
        } catch (_: DataIntegrityViolationException) {
            // A concurrent retry of the same delivery won the insert.
            return ReceiveOutcome.DUPLICATE
        }
        log.info("Queued inbound mail {} for user {} (verified={})", entity.id, userId, payload.verified)
        return ReceiveOutcome.QUEUED
    }

    private fun isVerified(userId: UUID, mail: ParsedInboundMail): Boolean {
        val from = mail.fromAddress ?: return false
        if (mail.dmarcPassDomain == null || mail.dmarcPassDomain != from.substringAfterLast('@')) return false
        val user = userRepository.findById(userId).orElse(null) ?: return false
        return user.emailVerifiedAt != null && user.emailHash == EmailHasher.hash(from)
    }

    private fun dedupKey(userId: UUID, messageId: String?, raw: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(userId.toString().toByteArray())
        digest.update(0)
        if (messageId != null) digest.update(messageId.toByteArray()) else digest.update(raw)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The queued message, as stored (encrypted) in `inbound_mail.payload`. */
data class InboundMailPayload(
    val subject: String,
    val fromAddress: String?,
    val fromName: String?,
    val body: String,
    val attachmentCount: Int,
    val verified: Boolean,
)
