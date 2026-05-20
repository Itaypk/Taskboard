package dev.itayp.tasker.planning

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Resolves the calendar-week start date that a planning session covers.
 *
 * `weekStartDay` defaults to [DayOfWeek.MONDAY] when null (matches ISO-8601). The result
 * is the most recent occurrence of `weekStartDay` on or before `today` for [WeekOffset.CURRENT],
 * or that date plus 7 for [WeekOffset.NEXT].
 */
object WeekResolver {

    fun resolveWeekStart(
        today: LocalDate,
        weekStartDay: DayOfWeek?,
        offset: WeekOffset,
    ): LocalDate {
        val effective = weekStartDay ?: DayOfWeek.MONDAY
        val daysBack = (today.dayOfWeek.value - effective.value + 7) % 7
        val currentWeekStart = today.minusDays(daysBack.toLong())
        return when (offset) {
            WeekOffset.CURRENT -> currentWeekStart
            WeekOffset.NEXT -> currentWeekStart.plusDays(7)
        }
    }

    fun parseWeekStartDay(value: String?): DayOfWeek? =
        value?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
}

enum class WeekOffset { CURRENT, NEXT }
