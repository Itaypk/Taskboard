package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryListener
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryState
import org.springframework.core.retry.RetryTemplate
import org.springframework.core.retry.Retryable
import org.springframework.mail.MailSendException
import java.time.Duration

/**
 * Retries transient SMTP failures in-process with exponential backoff. Only [MailSendException]
 * is retried: authentication, parse and message-preparation errors won't succeed on a second try.
 *
 * Must sit *inside* [EmailMetricsOutboundChannel] so `tasker.email.sent` records one final outcome
 * per email — otherwise a single recovered blip would register as a failure and trip alerts.
 * Recovered attempts are counted separately on `tasker.email.retries`.
 *
 * Retries live in memory and are lost on restart; at scheduling-email volume a durable outbox
 * isn't worth its complexity. Callers run on `@Async` virtual threads, so sleeping here is cheap.
 */
class RetryingOutboundChannel(
    private val delegate: OutboundChannel,
    private val meterRegistry: MeterRegistry,
    private val purpose: String,
    retryPolicy: RetryPolicy = DEFAULT_POLICY,
) : OutboundChannel {

    private val log = LoggerFactory.getLogger(RetryingOutboundChannel::class.java)

    private val retryTemplate = RetryTemplate(retryPolicy).apply {
        setRetryListener(object : RetryListener {
            override fun beforeRetry(retryPolicy: RetryPolicy, retryable: Retryable<*>, retryState: RetryState) {
                meterRegistry.counter("tasker.email.retries", "purpose", purpose).increment()
                // Exception class only: SMTP messages can echo recipient addresses.
                log.warn("Retrying {} email (retry #{}) after {}", purpose, retryState.retryCount, retryState.lastException?.javaClass?.simpleName)
            }
        })
    }

    override fun send(message: OutboundMessage) {
        try {
            retryTemplate.execute { delegate.send(message) }
        } catch (e: RetryException) {
            // Surface the real SMTP exception, not Spring's wrapper, to the metric and callers' logs.
            throw e.lastException
        }
    }

    companion object {
        /** 1 attempt + 3 retries over ~65s (5s, 15s, 45s). */
        val DEFAULT_POLICY: RetryPolicy = RetryPolicy.builder()
            .includes(MailSendException::class.java)
            .maxRetries(3)
            .delay(Duration.ofSeconds(5))
            .multiplier(3.0)
            .build()
    }
}
