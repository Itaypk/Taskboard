package dev.itayp.tasker.calendar.source

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtlCacheTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-26T10:00:00Z"))

    @Test
    fun `entries expire after the ttl`() {
        val cache = TtlCache<String, String>(Duration.ofMinutes(15), 10, clock)
        cache.put("a", "1")

        clock.now = clock.now.plus(Duration.ofMinutes(14))
        assertEquals("1", cache.get("a"))

        clock.now = clock.now.plus(Duration.ofMinutes(1))
        assertNull(cache.get("a"))
    }

    @Test
    fun `getOrLoad loads once while fresh`() {
        val cache = TtlCache<String, Int>(Duration.ofMinutes(15), 10, clock)
        var loads = 0

        repeat(3) { cache.getOrLoad("a") { ++loads } }

        assertEquals(1, loads)
    }

    @Test
    fun `evicts the oldest entry when full`() {
        val cache = TtlCache<String, String>(Duration.ofHours(1), 2, clock)
        cache.put("a", "1")
        clock.now = clock.now.plusSeconds(1)
        cache.put("b", "2")
        clock.now = clock.now.plusSeconds(1)
        cache.put("c", "3")

        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `invalidate drops an entry`() {
        val cache = TtlCache<String, String>(Duration.ofHours(1), 2, clock)
        cache.put("a", "1")
        cache.invalidate("a")
        assertNull(cache.get("a"))
    }
}
