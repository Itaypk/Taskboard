package dev.itayp.tasker.channel.email.invitation

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Issues iCalendar `SEQUENCE` values as seconds since [EPOCH], so every send for a UID — invite,
 * update or cancellation — outranks the one before it without storing a per-event counter.
 *
 * A stored counter would have to outlive the slot rows (which are rewritten on every plan edit) to
 * cover a slot moved away and back, where the reused UID must beat its earlier CANCEL. Wall-clock
 * time gives that for free, and it also outranks the fixed 0/1 values sent before this existed.
 * RFC 5545 describes the first SEQUENCE as 0, but mainstream clients only compare values.
 *
 * Call [next] when the change is decided — on the caller's thread, before the `@Async` hand-off —
 * so a delayed (e.g. retried) send can't carry a higher value than a later change to the same UID.
 * Fits in a signed int until ~2093.
 */
@Component
class ICalSequence(private val clock: Clock) {

    fun next(): Int = Duration.between(EPOCH, clock.instant()).seconds.toInt()

    companion object {
        val EPOCH: Instant = Instant.parse("2025-01-01T00:00:00Z")
    }
}
