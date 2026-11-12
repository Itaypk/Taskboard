package dev.itayp.tasker.calendar

import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.CalendarFeedEntity
import dev.itayp.tasker.repository.CalendarFeedRepository
import dev.itayp.tasker.util.CapabilityTokens
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A user's feed link, in the forms the settings card offers, plus when a calendar app last used it. */
data class CalendarFeedLink(
    /** `https://<base>/calendar/<token>.ics`: what "Copy link" copies. */
    val url: String,
    /** The same with the `webcal:` scheme, which Apple Calendar and Outlook open as a subscription. */
    val webcalUrl: String,
    /** Google Calendar's "add by URL" page, pre-filled. */
    val googleUrl: String,
    val createdAt: Instant,
    val lastFetchedAt: Instant?,
)

/**
 * The secret behind a user's calendar subscription feed (GitHub issue #346).
 *
 * The link is a capability: calendar apps can't send a session, so whoever has it can read the
 * user's planned blocks and their titles. It's a 256-bit [CapabilityTokens] secret, looked up by
 * its digest, and also kept encrypted under the user's DEK because the user needs to copy it again
 * (a second device, a second calendar app). It can be replaced at any time; the old link then
 * stops answering at once. Switching delivery away from the feed keeps the link (it serves an
 * empty calendar), so switching back needs no re-subscribing; deleting the account removes it.
 *
 * Never log a link or a token: log the user id only.
 */
@Service
class CalendarFeedService(
    private val repository: CalendarFeedRepository,
    private val userCryptoService: UserCryptoService,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(CalendarFeedService::class.java)

    @Transactional(readOnly = true)
    fun getLink(userId: UUID): CalendarFeedLink? =
        repository.findById(userId).orElse(null)?.toLink(userId)

    /** The user's link, creating one if they have none. */
    @Transactional
    fun getOrCreateLink(userId: UUID): CalendarFeedLink =
        repository.findById(userId).orElse(null)?.toLink(userId) ?: rotate(userId)

    /** Issues a new link, replacing the current one if there is one: the old link stops working. */
    @Transactional
    fun rotate(userId: UUID): CalendarFeedLink {
        val existing = repository.findById(userId).orElse(null)
        val token = CapabilityTokens.generate()
        val entity = (existing ?: CalendarFeedEntity().apply { this.userId = userId }).apply {
            tokenHash = CapabilityTokens.hash(token)
            this.token = userCryptoService.encrypt(userId, token)
            createdAt = clock.instant()
            lastFetchedAt = null
        }
        repository.save(entity)
        log.info("{} calendar feed link for user {}", if (existing == null) "Created" else "Replaced", userId)
        return entity.toLink(userId)
    }

    /**
     * The user a feed request is for, or null when the token isn't live (rotated, turned off, or
     * never existed). Records the fetch, at most every [FETCH_STAMP_INTERVAL], so a calendar app
     * polling every few minutes doesn't turn into a database write per request.
     */
    @Transactional
    fun resolveAndRecordFetch(token: String): UUID? {
        if (!TOKEN_SHAPE.matches(token)) return null
        val entity = repository.findByTokenHash(CapabilityTokens.hash(token)) ?: return null
        val now = clock.instant()
        val last = entity.lastFetchedAt
        if (last == null || Duration.between(last, now) >= FETCH_STAMP_INTERVAL) {
            entity.lastFetchedAt = now
            repository.save(entity)
        }
        return entity.userId
    }

    private fun CalendarFeedEntity.toLink(userId: UUID): CalendarFeedLink {
        val plainToken = checkNotNull(userCryptoService.decrypt(userId, token)) { "calendar feed token missing" }
        val url = "${appProperties.baseUrl.trimEnd('/')}$PATH_PREFIX$plainToken$SUFFIX"
        val webcalUrl = url.replaceFirst(Regex("^https?://"), "webcal://")
        return CalendarFeedLink(
            url = url,
            webcalUrl = webcalUrl,
            googleUrl = "https://calendar.google.com/calendar/r?cid=" + URLEncoder.encode(webcalUrl, Charsets.UTF_8),
            createdAt = checkNotNull(createdAt),
            lastFetchedAt = lastFetchedAt,
        )
    }

    companion object {
        const val PATH_PREFIX = "/calendar/"
        const val SUFFIX = ".ics"
        val FETCH_STAMP_INTERVAL: Duration = Duration.ofMinutes(5)

        /** `CapabilityTokens.generate`: 32 bytes, base64url without padding. */
        private val TOKEN_SHAPE = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
