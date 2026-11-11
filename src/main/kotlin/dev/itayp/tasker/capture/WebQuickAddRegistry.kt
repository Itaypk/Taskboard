package dev.itayp.tasker.capture

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of in-progress web quick-add captures, keyed by user: one capture per user at a
 * time, so starting a new one (another tab, another board) replaces the old. The web counterpart of
 * the Telegram `QuickAddRegistry`, with the same idle [TTL].
 *
 * In memory on purpose: a capture lives for seconds, and losing one to a restart costs the user a
 * retyped line (the controller answers 409 and the UI starts over).
 */
@Component
class WebQuickAddRegistry(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    /** A capture in progress: the board it saves to and where the flow stands. */
    data class Capture(val boardId: UUID, val state: QuickAddState)

    private val pending = ConcurrentHashMap<UUID, Capture>()

    fun get(userId: UUID): Capture? {
        val capture = pending[userId] ?: return null
        if (Duration.between(capture.state.createdAt, clock.instant()) > TTL) {
            pending.remove(userId)
            meterRegistry.counter("tasker.quickadd.outcome", "result", "expired").increment()
            return null
        }
        return capture
    }

    fun set(userId: UUID, capture: Capture) {
        pending[userId] = capture
    }

    fun remove(userId: UUID) {
        pending.remove(userId)
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
