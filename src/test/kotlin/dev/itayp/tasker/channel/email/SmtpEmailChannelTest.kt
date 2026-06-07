package dev.itayp.tasker.channel.email

import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mail.javamail.JavaMailSender
import jakarta.mail.Session
import java.util.Properties

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension::class)
class SmtpEmailChannelTest {

    private val mailSender: JavaMailSender = mock()
    private val channel = SmtpEmailChannel(mailSender, "sender@example.com", "Tasker")

    private fun newMimeMessage(): MimeMessage {
        val session = Session.getInstance(Properties())
        return MimeMessage(session)
    }

    @Test
    fun `sends simple HTML email when no iCal attachment`() {
        val mimeMessage = newMimeMessage()
        whenever(mailSender.createMimeMessage()).thenReturn(mimeMessage)

        channel.send(
            EmailMessage(
                to = listOf("user@example.com"),
                subject = "Hello",
                htmlBody = "<p>Hi</p>",
            ),
        )

        verify(mailSender).send(any<MimeMessage>())
        assertEquals("Hello", mimeMessage.subject)
    }

    @Test
    fun `sends multipart message when iCal attachment is present`() {
        val mimeMessage = newMimeMessage()
        whenever(mailSender.createMimeMessage()).thenReturn(mimeMessage)

        channel.send(
            EmailMessage(
                to = listOf("user@example.com"),
                subject = "Invite",
                htmlBody = "<p>Event</p>",
                iCalAttachment = ICalAttachment(
                    method = "REQUEST",
                    content = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
                ),
            ),
        )

        verify(mailSender).send(any<MimeMessage>())
        val content = mimeMessage.content
        assertTrue(content is MimeMultipart, "Expected multipart content, got ${content?.javaClass}")
        val multipart = content as MimeMultipart
        assertEquals(2, multipart.count, "Expected 2 parts (HTML + iCal)")
        val icalPart = multipart.getBodyPart(1)
        assertTrue(
            icalPart.contentType.contains("text/calendar", ignoreCase = true),
            "Second part should be text/calendar but was ${icalPart.contentType}",
        )
    }
}
