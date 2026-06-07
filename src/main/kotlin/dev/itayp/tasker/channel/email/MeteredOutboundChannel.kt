package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import io.micrometer.core.instrument.MeterRegistry

/**
 * Wraps an [OutboundChannel] to record a Prometheus counter for every send attempt,
 * tagged by `purpose` (auth / scheduling) and `outcome` (success / failure). This is
 * the email-delivery signal we can later hook to a Grafana alert — especially on the
 * critical auth path, where a delivery failure means a user can't log in.
 *
 * The counter is incremented around the delegate; a failure is counted and then
 * re-thrown so callers still see the error.
 */
class MeteredOutboundChannel(
    private val delegate: OutboundChannel,
    private val meterRegistry: MeterRegistry,
    private val purpose: String,
) : OutboundChannel {

    override fun send(message: OutboundMessage) {
        try {
            delegate.send(message)
            meterRegistry.counter("tasker.email.sent", "purpose", purpose, "outcome", "success").increment()
        } catch (e: Exception) {
            meterRegistry.counter("tasker.email.sent", "purpose", purpose, "outcome", "failure").increment()
            throw e
        }
    }
}
