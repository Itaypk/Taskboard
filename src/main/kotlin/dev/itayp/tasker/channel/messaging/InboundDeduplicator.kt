package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.channel.ChannelType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Remembers which provider message ids were already accepted, for [TTL].
 *
 * In memory is enough: we acknowledge a delivery before processing it, so a redelivery means the
 * acknowledgement was lost or late, which only happens while this instance is up. A restart loses
 * the memory, and with it at most a duplicate capture of a message in flight at the time.
 */
class InboundDeduplicator(private val clock: Clock) {
    private val seen = ConcurrentHashMap<String, Instant>()
    private val accepted = AtomicLong()

    /** True the first time an id is seen within [TTL], false for a repeat. */
    fun firstSeen(channel: ChannelType, eventId: String): Boolean {
        val now = clock.instant()
        if (accepted.incrementAndGet() % SWEEP_EVERY == 0L) sweep(now)
        val key = "${channel.metricTag}:$eventId"
        val previous = seen.putIfAbsent(key, now) ?: return true
        if (Duration.between(previous, now) <= TTL) return false
        // An old entry the sweep hasn't reached yet: the id counts as new again.
        seen[key] = now
        return true
    }

    private fun sweep(now: Instant) {
        seen.entries.removeIf { Duration.between(it.value, now) > TTL }
    }

    companion object {
        val TTL: Duration = Duration.ofHours(24)
        private const val SWEEP_EVERY = 500L
    }
}
