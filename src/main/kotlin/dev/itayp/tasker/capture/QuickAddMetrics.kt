package dev.itayp.tasker.capture

import io.micrometer.core.instrument.MeterRegistry

/**
 * `tasker.quickadd.outcome{result, channel}`: how each capture ended, and on which surface it was
 * made. `channel` is a [dev.itayp.tasker.channel.ChannelType.metricTag] for the interactive
 * surfaces (`telegram`, `web`, `dev`), or [CHANNEL_EMAIL] for inbound email, which has no
 * conversation channel.
 */
object QuickAddMetrics {
    const val OUTCOME = "tasker.quickadd.outcome"

    /** Inbound email captures (`docs/INBOUND-EMAIL.md`) save without a channel. */
    const val CHANNEL_EMAIL = "email"

    fun countOutcome(meterRegistry: MeterRegistry, result: String, channel: String) =
        meterRegistry.counter(OUTCOME, "result", result, "channel", channel).increment()
}
