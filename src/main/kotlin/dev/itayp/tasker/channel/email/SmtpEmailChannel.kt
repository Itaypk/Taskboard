package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import dev.itayp.tasker.service.MemberDisplayNameResolver
import jakarta.mail.Message.RecipientType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

class SmtpEmailChannel(
    private val mailSender: JavaMailSender,
    private val from: String,
    private val fromName: String,
) : OutboundChannel {

    private val log = LoggerFactory.getLogger(SmtpEmailChannel::class.java)

    override fun send(message: OutboundMessage) {
        require(message is EmailMessage) { "SmtpEmailChannel only handles EmailMessage" }
        val mime = mailSender.createMimeMessage()

        log.debug("Sending email to {}, from {}, subject='{}'", message.to.map { MemberDisplayNameResolver.maskEmail(it) }, from, message.subject.mask())

        if (message.iCalAttachment != null) {
            // Multipart/mixed: HTML body + text/calendar part for Gmail "Add to Calendar"
            mime.setFrom(InternetAddress(from, fromName, "UTF-8"))
            message.replyTo?.let { mime.replyTo = arrayOf(InternetAddress(it)) }
            mime.setSubject(message.subject, "UTF-8")
            for (to in message.to) {
                mime.addRecipients(RecipientType.TO, to)
            }
            val mixed = MimeMultipart("mixed")

            val htmlPart = MimeBodyPart().apply {
                setContent(message.htmlBody, "text/html; charset=UTF-8")
            }
            mixed.addBodyPart(htmlPart)

            val icalPart = MimeBodyPart().apply {
                val icalContentType = "text/calendar; method=${message.iCalAttachment.method}; charset=UTF-8"
                setContent(message.iCalAttachment.content, icalContentType)
                setHeader("Content-Type", icalContentType)
                setHeader("Content-Transfer-Encoding", "7bit")
                fileName = message.iCalAttachment.filename
            }
            mixed.addBodyPart(icalPart)

            mime.setContent(mixed)
        } else {
            val helper = MimeMessageHelper(mime, true, "UTF-8")
            helper.setFrom(from, fromName)
            message.replyTo?.let { helper.setReplyTo(it) }
            helper.setTo(message.to.toTypedArray())
            helper.setSubject(message.subject)
            if (message.textBody != null) {
                helper.setText(message.textBody, message.htmlBody)
            } else {
                helper.setText(message.htmlBody, true)
            }
        }

        message.inReplyTo?.let { setThreadHeaders(mime, it) }
        mailSender.send(mime)
        log.info("Email sent to {} subject='{}'", message.to.map { MemberDisplayNameResolver.maskEmail(it) }, message.subject.mask())
    }

    /**
     * Only a single `<…>` of visible ASCII: the value may come from an inbound email, and anything
     * with whitespace or line breaks could fold into extra headers.
     */
    private fun setThreadHeaders(mime: MimeMessage, messageId: String) {
        if (!MESSAGE_ID.matches(messageId)) {
            log.debug("Not threading: the Message-ID to reply to is malformed")
            return
        }
        mime.setHeader("In-Reply-To", messageId)
        mime.setHeader("References", messageId)
    }

    companion object {
        private val MESSAGE_ID = Regex("^<[!-;=?-~]{1,900}>$")

        fun String.mask() =
            takeIf { isNotBlank() }?.first()?.plus("*".repeat((this.length - 2).coerceIn(0, 6)))?.plus(last()) ?: "<empty>"

    }
}
