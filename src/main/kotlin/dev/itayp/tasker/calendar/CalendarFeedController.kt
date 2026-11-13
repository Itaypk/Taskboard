package dev.itayp.tasker.calendar

import dev.itayp.tasker.channel.email.invitation.ICalSequence
import dev.itayp.tasker.planning.PlanWatermarkService
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.service.UserSettingsService
import dev.itayp.tasker.util.CapabilityTokens
import dev.itayp.tasker.util.clientIp
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/**
 * The calendar subscription feed (GitHub issue #346): `GET /calendar/<token>.ics`.
 *
 * Unauthenticated by necessity (calendar apps can't send a session), so the token in the path is
 * the whole of the authentication; it's on its own stateless security chain, outside `/api/`.
 * An unknown token gets a plain 404 whatever the reason. A known token whose owner has switched
 * calendar delivery away from the feed gets an empty calendar rather than an error, so the
 * subscription survives and switching back needs no re-subscribing.
 *
 * Never log the path or the token (the hosted Nginx redacts it from the shipped access log too).
 */
@RestController
class CalendarFeedController(
    private val calendarFeedService: CalendarFeedService,
    private val calendarFeedContent: CalendarFeedContent,
    private val calendarFeedRenderer: CalendarFeedRenderer,
    private val userSettingsService: UserSettingsService,
    private val planWatermarkService: PlanWatermarkService,
    @Qualifier("calendarFeedRateLimiter") private val feedRateLimiter: RateLimiter,
    @Qualifier("calendarFeedMissRateLimiter") private val missRateLimiter: RateLimiter,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(CalendarFeedController::class.java)

    @GetMapping("/calendar/{token}.ics")
    fun feed(
        @PathVariable token: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val userId = calendarFeedService.resolveAndRecordFetch(token)
        if (userId == null) {
            // Only misses count here: a live link keeps working from an address that also sent junk.
            if (!missRateLimiter.tryConsume(request.clientIp())) return outcome("rate_limited", tooManyRequests())
            return outcome(
                "not_found",
                ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body("Not found\n"),
            )
        }
        if (!feedRateLimiter.tryConsume(CapabilityTokens.hash(token))) {
            log.info("Calendar feed rate-limited for user {}", userId)
            return outcome("rate_limited", tooManyRequests())
        }

        val now = clock.instant()
        val content = if (userSettingsService.getOrCreate(userId).calendarDelivery == CalendarDelivery.FEED) {
            calendarFeedContent.collect(userId, now.minus(PAST_WINDOW), now)
        } else {
            CalendarContent(emptyList(), emptyList())
        }
        val body = calendarFeedRenderer.render(content, planWatermarkService.read(userId) ?: ICalSequence.EPOCH)
        val etag = etagOf(body)
        log.debug("Served calendar feed for user {} ({} tasks, {} events)", userId, content.planTasks.size, content.oneOffEvents.size)

        if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim() == etag }) {
            return outcome("not_modified", ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build())
        }
        return outcome(
            "ok",
            ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .eTag(etag)
                .body(body),
        )
    }

    private fun tooManyRequests(): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.TEXT_PLAIN).body("Too many requests\n")

    private fun outcome(name: String, response: ResponseEntity<String>): ResponseEntity<String> {
        meterRegistry.counter("tasker.calendar_feed.fetches", "outcome", name).increment()
        return response
    }

    private fun etagOf(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        return "\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\""
    }

    companion object {
        /** How far back the feed reaches, so last week's blocks don't vanish the moment they end. */
        val PAST_WINDOW: Duration = Duration.ofDays(14)

        private val TEXT_CALENDAR = MediaType("text", "calendar", Charsets.UTF_8)
    }
}
