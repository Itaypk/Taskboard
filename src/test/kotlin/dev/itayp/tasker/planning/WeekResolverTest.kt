package dev.itayp.tasker.planning

import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeekResolverTest {

    @Test
    fun `current week resolves to most recent occurrence of weekStartDay`() {
        // 2026-05-13 is a Wednesday; Monday week-start → 2026-05-11
        val today = LocalDate.parse("2026-05-13")
        assertEquals(
            LocalDate.parse("2026-05-11"),
            WeekResolver.resolveWeekStart(today, DayOfWeek.MONDAY, WeekOffset.CURRENT),
        )
    }

    @Test
    fun `next week is current plus 7`() {
        val today = LocalDate.parse("2026-05-13")
        assertEquals(
            LocalDate.parse("2026-05-18"),
            WeekResolver.resolveWeekStart(today, DayOfWeek.MONDAY, WeekOffset.NEXT),
        )
    }

    @Test
    fun `today equals weekStartDay resolves to today for CURRENT`() {
        // 2026-05-11 is a Monday
        val today = LocalDate.parse("2026-05-11")
        assertEquals(today, WeekResolver.resolveWeekStart(today, DayOfWeek.MONDAY, WeekOffset.CURRENT))
    }

    @Test
    fun `null weekStartDay defaults to MONDAY`() {
        val today = LocalDate.parse("2026-05-13")
        assertEquals(
            WeekResolver.resolveWeekStart(today, DayOfWeek.MONDAY, WeekOffset.CURRENT),
            WeekResolver.resolveWeekStart(today, null, WeekOffset.CURRENT),
        )
    }

    @Test
    fun `Sunday weekStartDay wraps correctly when today is Saturday`() {
        // 2026-05-16 is a Saturday; Sunday week-start → previous Sunday 2026-05-10
        val today = LocalDate.parse("2026-05-16")
        assertEquals(
            LocalDate.parse("2026-05-10"),
            WeekResolver.resolveWeekStart(today, DayOfWeek.SUNDAY, WeekOffset.CURRENT),
        )
    }

    @Test
    fun `parseWeekStartDay accepts valid enum names and returns null otherwise`() {
        assertEquals(DayOfWeek.MONDAY, WeekResolver.parseWeekStartDay("MONDAY"))
        assertNull(WeekResolver.parseWeekStartDay(null))
        assertNull(WeekResolver.parseWeekStartDay("not-a-day"))
    }
}
