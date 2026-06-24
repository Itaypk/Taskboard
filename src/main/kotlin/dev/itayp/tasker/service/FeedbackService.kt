package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.FeedbackProperties
import dev.itayp.tasker.ratelimit.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * Collects in-app feedback and forwards it to the configured recipient over the existing
 * **auth** email channel (the same mailbox/credentials used for login & verification mail).
 *
 * Defences are deliberately light, matching the "small feature" scope:
 *  - bean validation caps the size on the way in (see `FeedbackRequest`);
 *  - a per-user rate limit blunts spamming;
 *  - a rudimentary junk check rejects content with no real characters.
 * CSRF is enforced by the default session security chain — the endpoint is authenticated and
 * not on the CSRF-exempt list.
 */
@Service
@EnableConfigurationProperties(FeedbackProperties::class)
class FeedbackService(
    @Qualifier("authEmailChannel") private val outboundChannel: OutboundChannel,
    private val emailTemplateEngine: EmailTemplateEngine,
    private val feedbackProperties: FeedbackProperties,
    private val emailProperties: EmailProperties,
    @Qualifier("feedbackRateLimiter") private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(FeedbackService::class.java)

    fun submit(userId: UUID, message: String, replyEmail: String?) {
        if (!rateLimiter.tryConsume(userId.toString())) {
            throw FeedbackRateLimitException()
        }

        val trimmedMessage = message.trim()
        // Rudimentary junk filter: a submission with no letters or digits at all (only whitespace
        // or stray punctuation) carries no signal. Deliberately permissive — a single real word
        // (in any script) is accepted. Bean validation already rejected the empty string.
        if (trimmedMessage.none { it.isLetterOrDigit() }) {
            throw FeedbackContentRejectedException()
        }

        val cleanedReplyEmail = replyEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val recipient = feedbackProperties.recipient.ifBlank { emailProperties.auth.from }
        if (recipient.isBlank()) {
            // No mailbox configured (e.g. local dev with email disabled). Don't fail the user —
            // the LoggingEmailChannel below still records the submission to the log.
            log.warn("Feedback received from userId={} but no recipient is configured", userId)
        }

        // Admin-facing email; English by design (not user-localised). Handlebars escapes the
        // {{...}} values, so user-authored content can't inject markup.
        val htmlBody = emailTemplateEngine.render(
            "emails/feedback.html",
            mapOf(
                "feedback_message" to trimmedMessage,
                "reply_email" to cleanedReplyEmail,
                "user_id" to userId.toString(),
                "submitted_at" to clock.instant().toString(),
            ),
            Locale.ENGLISH,
        )

        outboundChannel.send(
            EmailMessage(
                to = listOf(recipient),
                subject = "New Backlog.fyi feedback",
                htmlBody = htmlBody,
            )
        )

        log.info("Feedback submitted by userId={} (replyRequested={})", userId, cleanedReplyEmail != null)
    }
}

/** Thrown when feedback submissions exceed the rate limit. Maps to HTTP 429. */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class FeedbackRateLimitException : RuntimeException("Too much feedback sent; try again later")

/** Thrown when the feedback body is empty of meaningful content. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class FeedbackContentRejectedException : RuntimeException("Feedback looks empty; please add a few words")
