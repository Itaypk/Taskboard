package dev.itayp.tasker.channel.email.invitation

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ICalSequenceTest {

    private fun at(instant: String) = ICalSequence(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)).next()

    @Test
    fun `counts seconds since the epoch`() {
        assertEquals(0, at("2025-01-01T00:00:00Z"))
        assertEquals(86_400, at("2025-01-02T00:00:00Z"))
    }

    @Test
    fun `a later change outranks an earlier one and the legacy fixed values`() {
        val invite = at("2026-05-10T12:00:00Z")
        val update = at("2026-05-10T12:00:01Z")
        assertTrue(update > invite)
        assertTrue(invite > 1, "must outrank the SEQUENCE:0/1 sent before time-based sequencing")
    }
}
