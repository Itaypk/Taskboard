package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.capture.QuickAddMetrics
import dev.itayp.tasker.capture.QuickAddState
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of in-progress captures on messaging channels, keyed by [ConversationKey]. The
 * counterpart of Telegram's `QuickAddRegistry` (keyed by chat id) and the web's
 * `WebQuickAddRegistry` (which also holds a board); deliberately a separate small class, since no
 * caller is polymorphic over them.
 *
 * Entries expire after [TTL] of inactivity, observed lazily on the next [get] and counted as an
 * `expired` quick-add outcome, so a stale draft card can't swallow an unrelated later message.
 */
@Component
class MessagingCaptureRegistry(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val pending = ConcurrentHashMap<ConversationKey, QuickAddState>()

    fun get(key: ConversationKey): QuickAddState? {
        val state = pending[key] ?: return null
        if (Duration.between(state.createdAt, clock.instant()) > TTL) {
            pending.remove(key)
            QuickAddMetrics.countOutcome(meterRegistry, "expired", key.channel.metricTag)
            return null
        }
        return state
    }

    fun set(key: ConversationKey, state: QuickAddState?) {
        if (state != null) pending[key] = state else pending.remove(key)
    }

    fun remove(key: ConversationKey) {
        pending.remove(key)
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
