package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.capture.QuickAddState
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of in-progress quick-add ("/add") flows, keyed by Telegram chat id — the
 * chat-id keying analogue to [dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry].
 * The flow state itself ([QuickAddState]) is channel-agnostic; only this storage layer is
 * Telegram-specific.
 *
 * Entries expire after [TTL] of inactivity: a [get] past the deadline drops the entry and counts it
 * as an `expired` quick-add outcome, so a stale draft card can't swallow an unrelated later message.
 * (Expiry is observed lazily, on the next access for that chat, so the counter is a lower bound.)
 */
@Component
@ConditionalOnTelegramBot
class QuickAddRegistry(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val pending = ConcurrentHashMap<Long, QuickAddState>()

    fun get(chatId: Long): QuickAddState? {
        val state = pending[chatId] ?: return null
        if (Duration.between(state.createdAt, clock.instant()) > TTL) {
            pending.remove(chatId)
            meterRegistry.counter("tasker.quickadd.outcome", "result", "expired").increment()
            return null
        }
        return state
    }

    fun set(chatId: Long, state: QuickAddState) {
        pending[chatId] = state
    }

    fun remove(chatId: Long) {
        pending.remove(chatId)
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
