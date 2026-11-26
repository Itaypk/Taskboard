package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.config.RateLimitProperties
import dev.itayp.tasker.crypto.ChannelLinkHasher
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.ChannelLinkEntity
import dev.itayp.tasker.jpa.ChannelLinkTokenEntity
import dev.itayp.tasker.ratelimit.InMemoryRateLimiter
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.repository.ChannelLinkRepository
import dev.itayp.tasker.repository.ChannelLinkTokenRepository
import dev.itayp.tasker.repository.UserRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A freshly issued link code, as the user should type it (`ABCD-EFGH`). */
data class LinkCode(val code: String, val expiresAt: Instant)

/** A user's link on one channel, ready to show: [display] is already masked. */
data class ChannelLinkView(val channel: ChannelType, val display: String?, val linkedAt: Instant)

/** What a code-shaped message from a chat account did. */
sealed interface LinkOutcome {
    /** The chat account is now linked to [userId] (or already was). */
    data class Linked(val userId: UUID, val alreadyLinked: Boolean) : LinkOutcome

    /** Unknown, expired or already used. */
    data object Invalid : LinkOutcome

    /** The chat account is linked to another user, which must disconnect it first. No merging. */
    data class Conflict(val linkedUserId: UUID) : LinkOutcome

    /** Too many attempts from this chat account: dropped without a reply. */
    data object RateLimited : LinkOutcome
}

/** Only claimed accounts may link a chat account (an unclaimed demo account is reaped). */
class ChannelLinkNotAllowedException : RuntimeException("Only claimed accounts can link a messaging channel")

/**
 * Links chat accounts on messaging channels to users (`docs/MESSAGING-CHANNELS.md`, section 3).
 *
 * The user asks for a code in Settings ([startLink]) and sends it to the bot from the chat account;
 * the capture router hands every code-shaped message to [consume]. The flow is inbound-first by
 * design: an unverified WhatsApp business can't send one-time codes, and the user's message also
 * opens WhatsApp's 24-hour reply window.
 *
 * Never log a code, a chat account id or a phone number: user ids and channels only.
 */
@Service
class ChannelLinkService(
    private val linkRepository: ChannelLinkRepository,
    private val tokenRepository: ChannelLinkTokenRepository,
    private val userRepository: UserRepository,
    private val userCryptoService: UserCryptoService,
    private val hasher: ChannelLinkHasher,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    rateLimitProperties: RateLimitProperties,
) {
    private val log = LoggerFactory.getLogger(ChannelLinkService::class.java)
    private val random = SecureRandom()
    private val attemptLimiter: RateLimiter = rateLimitProperties.channelLinkAttempt.let {
        InMemoryRateLimiter(it.limit, it.windowSeconds * 1000)
    }

    /** Issues a link code for [channel], replacing the user's earlier one if any. */
    @Transactional
    fun startLink(userId: UUID, channel: ChannelType): LinkCode {
        val user = userRepository.findById(userId).orElseThrow()
        if (!user.claimed) throw ChannelLinkNotAllowedException()
        val code = generateCode()
        val expiresAt = clock.instant().plus(CODE_TTL)
        val token = tokenRepository.findByUserIdAndChannel(userId, channel)
            ?: ChannelLinkTokenEntity().apply {
                this.userId = userId
                this.channel = channel
            }
        token.tokenHmac = hasher.hash(code)
        token.expiresAt = expiresAt
        tokenRepository.save(token)
        log.debug("Issued a {} link code for user {}", channel.metricTag, userId)
        return LinkCode("${code.take(4)}-${code.drop(4)}", expiresAt)
    }

    /**
     * Handles a code-shaped message (see [extractCode]) sent by [senderId] on [channel]. [display]
     * is what Settings will show for the account (a phone number), when the platform sent one.
     */
    @Transactional
    fun consume(channel: ChannelType, senderId: String, display: String?, code: String): LinkOutcome {
        if (!attemptLimiter.tryConsume("${channel.metricTag}:${hasher.hash(senderId)}")) {
            return count(channel, LinkOutcome.RateLimited)
        }
        val now = clock.instant()
        val token = tokenRepository.findByTokenHmac(hasher.hash(code))
            ?.takeIf { it.channel == channel && it.expiresAt!!.isAfter(now) }
            ?: return count(channel, LinkOutcome.Invalid)
        val userId = token.userId!!
        val senderHmac = hasher.hash(senderId)

        val existing = linkRepository.findByChannelAndExternalIdHmac(channel, senderHmac)
        if (existing != null && existing.userId != userId) {
            return count(channel, LinkOutcome.Conflict(existing.userId!!))
        }
        tokenRepository.delete(token)
        if (existing != null) return count(channel, LinkOutcome.Linked(userId, alreadyLinked = true))

        // One chat account per user per channel: linking a new one replaces the old.
        linkRepository.findByUserIdAndChannel(userId, channel)?.let {
            linkRepository.delete(it)
            linkRepository.flush()
        }
        linkRepository.save(
            ChannelLinkEntity().apply {
                this.userId = userId
                this.channel = channel
                externalIdHmac = senderHmac
                externalId = userCryptoService.encrypt(userId, senderId)
                this.display = display?.let { userCryptoService.encrypt(userId, it) }
                linkedAt = now
            },
        )
        log.info("Linked a {} account to user {}", channel.metricTag, userId)
        return count(channel, LinkOutcome.Linked(userId, alreadyLinked = false))
    }

    /** The user [senderId] is linked to on [channel], or null. */
    @Transactional(readOnly = true)
    fun resolve(channel: ChannelType, senderId: String): UUID? =
        linkRepository.findByChannelAndExternalIdHmac(channel, hasher.hash(senderId))?.userId

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<ChannelLinkView> =
        linkRepository.findByUserId(userId).map { link ->
            ChannelLinkView(
                channel = link.channel!!,
                display = userCryptoService.decrypt(userId, link.display)?.let(::mask),
                linkedAt = link.linkedAt!!,
            )
        }

    /** Disconnects the user's chat account on [channel]. Returns false when there was none. */
    @Transactional
    fun unlink(userId: UUID, channel: ChannelType): Boolean {
        val link = linkRepository.findByUserIdAndChannel(userId, channel) ?: return false
        linkRepository.delete(link)
        meterRegistry.counter(LINK_METRIC, "channel", channel.metricTag, "outcome", "unlinked").increment()
        log.info("Unlinked the {} account of user {}", channel.metricTag, userId)
        return true
    }

    private fun count(channel: ChannelType, outcome: LinkOutcome): LinkOutcome {
        val tag = when (outcome) {
            is LinkOutcome.Linked -> if (outcome.alreadyLinked) "already_linked" else "linked"
            LinkOutcome.Invalid -> "invalid"
            is LinkOutcome.Conflict -> "conflict"
            LinkOutcome.RateLimited -> "rate_limited"
        }
        meterRegistry.counter(LINK_METRIC, "channel", channel.metricTag, "outcome", tag).increment()
        return outcome
    }

    /** Always with a digit, so no ordinary word ("homework") can ever be taken for a code. */
    private fun generateCode(): String =
        generateSequence { String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) }
            .first { code -> code.any { it.isDigit() } }

    companion object {
        const val LINK_METRIC = "tasker.channel.link"
        val CODE_TTL: Duration = Duration.ofMinutes(15)
        private const val CODE_LENGTH = 8

        /** RFC 4648 base32: no 0/1/8/9, so nothing to confuse with O/I/B/g. */
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** The code with its dash, anywhere in a short message (a prefilled `wa.me` text says more). */
        private val DASHED = Regex("(?<![A-Za-z0-9])([A-Za-z2-7]{4})-([A-Za-z2-7]{4})(?![A-Za-z0-9])")

        /** Without the dash only as the whole message. */
        private val BARE = Regex("^\\s*([A-Za-z2-7]{8})\\s*$")

        /** Messages longer than this are never codes: a prefilled link text is one short sentence. */
        private const val MAX_CODE_MESSAGE_LENGTH = 200

        /**
         * The normalized code in [text] (uppercase, no dash), or null when it doesn't carry one.
         * Codes always contain a digit, so a word that happens to fit the shape is never one.
         */
        fun extractCode(text: String): String? {
            if (text.length > MAX_CODE_MESSAGE_LENGTH) return null
            val code = DASHED.find(text)?.let { it.groupValues[1] + it.groupValues[2] }
                ?: BARE.matchEntire(text)?.groupValues?.get(1)
                ?: return null
            return code.uppercase().takeIf { candidate -> candidate.any { it.isDigit() } }
        }

        /** Keeps the last 4 characters: enough to recognize a phone number, not to dial it. */
        fun mask(display: String): String = if (display.length <= 4) display else "•••• ${display.takeLast(4)}"
    }
}
