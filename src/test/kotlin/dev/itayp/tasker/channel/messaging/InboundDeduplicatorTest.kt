package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.channel.ChannelType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class InboundDeduplicatorTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
    }

    private val clock = MutableClock(Instant.parse("2026-11-26T12:00:00Z"))
    private val deduplicator = InboundDeduplicator(clock)

    @Test
    fun `an id is new once per channel within the window, and new again after it`() {
        assertThat(deduplicator.firstSeen(ChannelType.WHATSAPP, "wamid.1")).isTrue()
        assertThat(deduplicator.firstSeen(ChannelType.WHATSAPP, "wamid.1")).isFalse()
        assertThat(deduplicator.firstSeen(ChannelType.TELEGRAM, "wamid.1")).isTrue()

        clock.now = clock.now.plus(InboundDeduplicator.TTL).plusSeconds(1)
        assertThat(deduplicator.firstSeen(ChannelType.WHATSAPP, "wamid.1")).isTrue()
    }
}
