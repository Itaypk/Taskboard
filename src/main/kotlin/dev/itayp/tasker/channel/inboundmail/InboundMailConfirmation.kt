package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.capture.SavedCapture
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.util.Locale
import java.util.UUID

/**
 * Tells the user what an inbound email became. Over Telegram when the bot can reach them (where
 * quick-add confirmations already live), otherwise by email to the account's own verified
 * address, otherwise not at all: the tasks are on the board either way.
 *
 * Never to the email's sender or `From:`: that is how inbound mail loops and backscatter start,
 * and the sender may well be a stranger. A message that wasn't verifiably from the user's own
 * address says so, with a nudge to change the address if it looks unfamiliar.
 */
@Component
class InboundMailConfirmation(
    private val channelResolver: ScheduledConversationChannelResolver,
    @Qualifier("schedulingEmailChannel") private val emailChannel: OutboundChannel,
    private val emailTemplateEngine: EmailTemplateEngine,
    private val emailProperties: EmailProperties,
    private val userRepository: UserRepository,
    private val userCryptoService: UserCryptoService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(InboundMailConfirmation::class.java)

    /** [attachmentsRead]: the capture read the email's images and PDFs, rather than its text only. */
    fun sendSaved(userId: UUID, payload: InboundMailPayload, saved: SavedCapture, attachmentsRead: Boolean) =
        send(userId, payload, saved.titles, attachmentsRead)

    fun sendFailure(userId: UUID, payload: InboundMailPayload) =
        send(userId, payload, titles = null, attachmentsRead = false)

    /** [titles] null means nothing was created. Best effort: a failed confirmation is only logged. */
    private fun send(userId: UUID, payload: InboundMailPayload, titles: List<String>?, attachmentsRead: Boolean) {
        try {
            val locale = userSettingsService.getLocale(userId)
            val subject = payload.subject.ifBlank { "…" }.take(SUBJECT_CHARS)
            val notes = notes(locale, payload, created = titles != null, attachmentsRead)
            val telegram = channelResolver.resolve(userId)?.channel
            if (telegram != null) {
                telegram.send(ChannelMessage.Text(telegramText(locale, subject, titles, notes, telegram.formatter::escape)))
                return
            }
            val email = verifiedEmail(userId) ?: run {
                log.debug("No channel to confirm inbound mail on for user {}", userId)
                return
            }
            emailChannel.send(composeEmail(email, locale, subject, titles, notes))
        } catch (e: Exception) {
            log.warn("Inbound mail confirmation for user {} failed: {}", userId, e.message)
        }
    }

    private fun telegramText(
        locale: Locale,
        subject: String,
        titles: List<String>?,
        notes: List<String>,
        escape: (String) -> String,
    ): String = buildString {
        append("📧 ")
        if (titles == null) {
            append(msg("inboundmail.failed", locale, escape(subject)))
        } else {
            append(msg("inboundmail.saved.intro", locale, escape(subject)))
            titles.forEach { append("\n• <b>").append(escape(it)).append("</b>") }
        }
        notes.forEach { append("\n\n").append(escape(it)) }
    }

    private fun composeEmail(
        to: String,
        locale: Locale,
        subject: String,
        titles: List<String>?,
        notes: List<String>,
    ): EmailMessage {
        val html = emailTemplateEngine.render(
            "emails/inbound-mail.html",
            mapOf(
                "failed" to (titles == null),
                "subject" to subject,
                "titles" to titles.orEmpty(),
                "notes" to notes,
                "app_url" to appProperties.baseUrl,
            ),
            locale,
        )
        val text = buildString {
            if (titles == null) {
                append(msg("inboundmail.failed", locale, subject))
            } else {
                append(msg("inboundmail.saved.intro", locale, subject))
                titles.forEach { append("\n- ").append(it) }
            }
            notes.forEach { append("\n\n").append(it) }
            append("\n\n").append(appProperties.baseUrl)
        }
        return EmailMessage(
            to = listOf(to),
            subject = msg(if (titles == null) "email.inboundmail.failed.subject" else "email.inboundmail.subject", locale),
            htmlBody = html,
            textBody = text,
        )
    }

    /**
     * The fine print under the result. Attachments are only worth a word when something was created:
     * which ones were left out (type or caps), and, when the capture used the text alone, that the
     * readable ones weren't read either.
     */
    private fun notes(locale: Locale, payload: InboundMailPayload, created: Boolean, attachmentsRead: Boolean): List<String> =
        buildList {
            if (created) {
                if (payload.unreadAttachments.isNotEmpty()) {
                    add(msg(
                        "inboundmail.attachments.unread", locale,
                        payload.unreadAttachments.joinToString(", ") { it.take(FILENAME_CHARS) },
                        InboundMailParser.MAX_ATTACHMENT_BYTES / (1024 * 1024),
                        InboundMailParser.MAX_ATTACHMENTS,
                    ))
                }
                val readable = payload.attachmentCount - payload.unreadAttachments.size
                if (readable > 0 && !attachmentsRead) add(msg("inboundmail.attachments", locale))
            }
            if (!payload.verified) add(msg("inboundmail.unverified", locale))
        }

    private fun verifiedEmail(userId: UUID): String? {
        if (!emailProperties.enabled) return null
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (user.emailVerifiedAt == null) return null
        return userCryptoService.decrypt(userId, user.email)?.takeIf { it.isNotBlank() }
    }

    private fun msg(key: String, locale: Locale, vararg args: Any): String =
        messageSource.getMessage(key, args, locale)

    private companion object {
        const val SUBJECT_CHARS = 120
        const val FILENAME_CHARS = 60
    }
}
