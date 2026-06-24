package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.FeedbackProperties
import dev.itayp.tasker.ratelimit.RateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class FeedbackServiceTest {

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val clock = Clock.fixed(Instant.parse("2026-06-24T10:00:00Z"), ZoneOffset.UTC)

    private val outboundChannel = mock<OutboundChannel>()
    private val templateEngine = mock<EmailTemplateEngine>()
    private val rateLimiter = mock<RateLimiter>()

    @BeforeEach
    fun setUp() {
        whenever(templateEngine.render(any(), any(), any())).thenReturn("<html>rendered</html>")
        whenever(rateLimiter.tryConsume(any())).thenReturn(true)
    }

    private fun service(
        feedbackProps: FeedbackProperties = FeedbackProperties(recipient = "owner@example.com"),
        emailProps: EmailProperties = EmailProperties(),
    ) = FeedbackService(outboundChannel, templateEngine, feedbackProps, emailProps, rateLimiter, clock)

    @Test
    fun `sends feedback email to configured recipient`() {
        service().submit(userId, "The drag and drop feels great!", "me@example.com")

        val captor = argumentCaptor<EmailMessage>()
        verify(outboundChannel).send(captor.capture())
        assertThat(captor.firstValue.to).containsExactly("owner@example.com")
        assertThat(captor.firstValue.subject).contains("feedback")
        // The submitter's address rides along as Reply-To so the owner can reply directly.
        assertThat(captor.firstValue.replyTo).isEqualTo("me@example.com")
    }

    @Test
    fun `leaves reply-to null when no reply email is given`() {
        service().submit(userId, "No reply needed", null)

        val captor = argumentCaptor<EmailMessage>()
        verify(outboundChannel).send(captor.capture())
        assertThat(captor.firstValue.replyTo).isNull()
    }

    @Test
    fun `falls back to auth sender address when recipient is blank`() {
        val emailProps = EmailProperties(auth = EmailProperties.SenderConfig(from = "noreply@backlog.fyi"))
        service(feedbackProps = FeedbackProperties(recipient = ""), emailProps = emailProps)
            .submit(userId, "Hello there", null)

        val captor = argumentCaptor<EmailMessage>()
        verify(outboundChannel).send(captor.capture())
        assertThat(captor.firstValue.to).containsExactly("noreply@backlog.fyi")
    }

    @Test
    fun `passes trimmed message and normalised reply email to the template`() {
        service().submit(userId, "  trim me  ", "  ME@Example.com ")

        val modelCaptor = argumentCaptor<Map<String, Any?>>()
        verify(templateEngine).render(eq("emails/feedback.html"), modelCaptor.capture(), eq(Locale.ENGLISH))
        assertThat(modelCaptor.firstValue["feedback_message"]).isEqualTo("trim me")
        assertThat(modelCaptor.firstValue["reply_email"]).isEqualTo("me@example.com")
    }

    @Test
    fun `rejects content with no meaningful characters`() {
        assertThrows<FeedbackContentRejectedException> {
            service().submit(userId, "   .   ", null)
        }
        verify(outboundChannel, never()).send(any())
    }

    @Test
    fun `throws when rate limit is exceeded`() {
        whenever(rateLimiter.tryConsume(any())).thenReturn(false)

        assertThrows<FeedbackRateLimitException> {
            service().submit(userId, "valid feedback", null)
        }
        verify(outboundChannel, never()).send(any())
    }
}
