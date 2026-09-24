package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.retry.RetryPolicy
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailSendException
import java.time.Duration

/** Exercises the production stack: metrics outside, retry inside. */
class RetryingOutboundChannelTest {

    private val delegate: OutboundChannel = mock()
    private val meterRegistry = SimpleMeterRegistry()
    private val policy = RetryPolicy.builder()
        .includes(MailSendException::class.java)
        .maxRetries(2)
        .delay(Duration.ZERO)
        .build()
    private val channel = EmailMetricsOutboundChannel(
        RetryingOutboundChannel(delegate, meterRegistry, "scheduling", policy),
        meterRegistry,
        "scheduling",
    )
    private val message = EmailMessage(to = listOf("user@example.com"), subject = "s", htmlBody = "<p/>")

    @Test
    fun `recovered transient failure counts as a single success`() {
        doThrow(MailSendException("blip")).doNothing().whenever(delegate).send(any())

        channel.send(message)

        verify(delegate, times(2)).send(message)
        assertEquals(1.0, sent("success"))
        assertEquals(0.0, sent("failure"))
        assertEquals(1.0, retries())
    }

    @Test
    fun `exhausted retries count one failure and rethrow the original exception`() {
        val smtpError = MailSendException("down")
        doThrow(smtpError).whenever(delegate).send(any())

        val thrown = assertThrows<MailSendException> { channel.send(message) }

        assertSame(smtpError, thrown)
        verify(delegate, times(3)).send(message)
        assertEquals(0.0, sent("success"))
        assertEquals(1.0, sent("failure"))
        assertEquals(2.0, retries())
    }

    @Test
    fun `non-transient failure is not retried`() {
        doThrow(MailAuthenticationException("bad credentials")).whenever(delegate).send(any())

        assertThrows<MailAuthenticationException> { channel.send(message) }

        verify(delegate, times(1)).send(message)
        assertEquals(1.0, sent("failure"))
        assertEquals(0.0, retries())
    }

    @Test
    fun `first-try success does not retry`() {
        doNothing().whenever(delegate).send(any())

        channel.send(message)

        verify(delegate, times(1)).send(message)
        assertEquals(1.0, sent("success"))
        assertEquals(0.0, retries())
    }

    private fun sent(outcome: String) =
        meterRegistry.find("tasker.email.sent").tags("purpose", "scheduling", "outcome", outcome).counter()?.count() ?: 0.0

    private fun retries() =
        meterRegistry.find("tasker.email.retries").tags("purpose", "scheduling").counter()?.count() ?: 0.0
}
