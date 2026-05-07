package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import jakarta.mail.Message.RecipientType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

class SmtpEmailChannel(
    private val mailSender: JavaMailSender,
    private val properties: EmailProperties,
) : OutboundChannel {

    private val log = LoggerFactory.getLogger(SmtpEmailChannel::class.java)

    override fun send(message: OutboundMessage) {
        require(message is EmailMessage) { "SmtpEmailChannel only handles EmailMessage" }
        val mime = mailSender.createMimeMessage()

        if (message.iCalAttachment != null) {
            // Multipart/mixed: HTML body + text/calendar part for Gmail "Add to Calendar"
            mime.setFrom(InternetAddress(properties.from, properties.fromName, "UTF-8"))
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
            helper.setFrom(properties.from, properties.fromName)
            helper.setTo(message.to.toTypedArray())
            helper.setSubject(message.subject)
            if (message.textBody != null) {
                helper.setText(message.textBody, message.htmlBody)
            } else {
                helper.setText(message.htmlBody, true)
            }
        }

        mailSender.send(mime)
        log.info("Email sent to {} subject='{}'", message.to, message.subject)
    }
}
