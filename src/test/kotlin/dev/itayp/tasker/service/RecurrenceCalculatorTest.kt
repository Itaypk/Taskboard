package dev.itayp.tasker.service

import dev.itayp.tasker.model.RecurrenceKind
import dev.itayp.tasker.model.TaskRecurrence
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecurrenceCalculatorTest {

    private fun d(text: String) = LocalDate.parse(text)

    // --- completion-anchored ---

    @Test
    fun `every N days counts from completion`() {
        val rule = TaskRecurrence(RecurrenceKind.EVERY_N_DAYS, every = 45)
        assertEquals(d("2026-10-27"), RecurrenceCalculator.nextOccurrence(rule, d("2026-09-01"), d("2026-09-12")))
    }

    @Test
    fun `every N months counts from completion and ignores the scheduled occurrence`() {
        val rule = TaskRecurrence(RecurrenceKind.EVERY_N_MONTHS, every = 6)
        assertEquals(d("2026-09-10"), RecurrenceCalculator.nextOccurrence(rule, d("2026-03-01"), d("2026-03-10")))
        // Late: the clock restarts from the real visit.
        assertEquals(d("2027-04-02"), RecurrenceCalculator.nextOccurrence(rule, d("2026-09-10"), d("2026-10-02")))
    }

    @Test
    fun `every N months clamps month-end`() {
        val rule = TaskRecurrence(RecurrenceKind.EVERY_N_MONTHS, every = 1)
        assertEquals(d("2027-02-28"), RecurrenceCalculator.nextOccurrence(rule, null, d("2027-01-31")))
        assertEquals(d("2028-02-29"), RecurrenceCalculator.nextOccurrence(rule, null, d("2028-01-31")))
    }

    // --- calendar-anchored ---

    @Test
    fun `monthly rent paid late within the month moves to next month`() {
        val rule = TaskRecurrence(RecurrenceKind.MONTHLY, day = 25)
        assertEquals(d("2026-10-25"), RecurrenceCalculator.nextOccurrence(rule, d("2026-09-25"), d("2026-09-27")))
    }

    @Test
    fun `monthly merges occurrences missed while the task sat open`() {
        val rule = TaskRecurrence(RecurrenceKind.MONTHLY, day = 25)
        assertEquals(d("2026-11-25"), RecurrenceCalculator.nextOccurrence(rule, d("2026-10-25"), d("2026-11-03")))
    }

    @Test
    fun `completing early counts toward the pending occurrence`() {
        val rule = TaskRecurrence(RecurrenceKind.MONTHLY, day = 25)
        // Occurrence Oct 25 is still in the future; paying on Oct 20 settles October, not September.
        assertEquals(d("2026-11-25"), RecurrenceCalculator.nextOccurrence(rule, d("2026-10-25"), d("2026-10-20")))
    }

    @Test
    fun `completing on the occurrence day moves to the next match`() {
        val rule = TaskRecurrence(RecurrenceKind.MONTHLY, day = 25)
        assertEquals(d("2026-10-25"), RecurrenceCalculator.nextOccurrence(rule, d("2026-09-25"), d("2026-09-25")))
    }

    @Test
    fun `monthly day 31 means the last day of the month`() {
        val rule = TaskRecurrence(RecurrenceKind.MONTHLY, day = 31)
        assertEquals(d("2027-02-28"), RecurrenceCalculator.nextOccurrence(rule, d("2027-01-31"), d("2027-01-31")))
        assertEquals(d("2027-03-31"), RecurrenceCalculator.nextOccurrence(rule, d("2027-02-28"), d("2027-02-28")))
        assertEquals(d("2027-04-30"), RecurrenceCalculator.nextOccurrence(rule, d("2027-03-31"), d("2027-04-01")))
    }

    @Test
    fun `weekly finds the next matching weekday`() {
        val tuesday = TaskRecurrence(RecurrenceKind.WEEKLY, day = 2)
        // 2026-09-15 is a Tuesday.
        assertEquals(d("2026-09-22"), RecurrenceCalculator.nextOccurrence(tuesday, d("2026-09-15"), d("2026-09-15")))
        assertEquals(d("2026-09-22"), RecurrenceCalculator.nextOccurrence(tuesday, d("2026-09-15"), d("2026-09-17")))
    }

    @Test
    fun `yearly rolls to next year and clamps leap day`() {
        val insurance = TaskRecurrence(RecurrenceKind.YEARLY, month = 3, day = 14)
        assertEquals(d("2027-03-14"), RecurrenceCalculator.nextOccurrence(insurance, d("2026-03-14"), d("2026-03-20")))

        val leap = TaskRecurrence(RecurrenceKind.YEARLY, month = 2, day = 29)
        assertEquals(d("2029-02-28"), RecurrenceCalculator.nextOccurrence(leap, d("2028-02-29"), d("2028-03-01")))
        assertEquals(d("2032-02-29"), RecurrenceCalculator.nextOccurrence(leap, d("2031-02-28"), d("2031-02-28")))
    }

    // --- first occurrence & deadline ---

    @Test
    fun `first occurrence is today for interval rules and the next match for calendar rules`() {
        val today = d("2026-09-13")
        assertEquals(today, RecurrenceCalculator.firstOccurrence(TaskRecurrence(RecurrenceKind.EVERY_N_MONTHS, every = 6), today))
        assertEquals(d("2026-09-25"), RecurrenceCalculator.firstOccurrence(TaskRecurrence(RecurrenceKind.MONTHLY, day = 25), today))
        assertEquals(today, RecurrenceCalculator.firstOccurrence(TaskRecurrence(RecurrenceKind.MONTHLY, day = 13), today))
        assertEquals(d("2027-03-14"), RecurrenceCalculator.firstOccurrence(TaskRecurrence(RecurrenceKind.YEARLY, month = 3, day = 14), today))
    }

    @Test
    fun `deadline is relative to the occurrence`() {
        assertEquals(d("2026-10-02"), RecurrenceCalculator.deadlineFor(TaskRecurrence(RecurrenceKind.MONTHLY, day = 25, dueWithinDays = 7), d("2026-09-25")))
        assertNull(RecurrenceCalculator.deadlineFor(TaskRecurrence(RecurrenceKind.MONTHLY, day = 25), d("2026-09-25")))
    }

    // --- validation ---

    @Test
    fun `validation accepts well-formed rules`() {
        listOf(
            TaskRecurrence(RecurrenceKind.EVERY_N_DAYS, every = 1),
            TaskRecurrence(RecurrenceKind.EVERY_N_MONTHS, every = 120, dueWithinDays = 0),
            TaskRecurrence(RecurrenceKind.WEEKLY, day = 7),
            TaskRecurrence(RecurrenceKind.MONTHLY, day = 31, dueWithinDays = 365),
            TaskRecurrence(RecurrenceKind.YEARLY, month = 2, day = 29),
        ).forEach { assertNull(it.validationError(), "expected $it to be valid") }
    }

    @Test
    fun `validation rejects missing, out-of-range and foreign fields`() {
        listOf(
            TaskRecurrence(RecurrenceKind.EVERY_N_DAYS),
            TaskRecurrence(RecurrenceKind.EVERY_N_DAYS, every = 0),
            TaskRecurrence(RecurrenceKind.EVERY_N_DAYS, every = 3, day = 2),
            TaskRecurrence(RecurrenceKind.WEEKLY, day = 8),
            TaskRecurrence(RecurrenceKind.MONTHLY, day = 0),
            TaskRecurrence(RecurrenceKind.MONTHLY, day = 5, month = 2),
            TaskRecurrence(RecurrenceKind.YEARLY, day = 5),
            TaskRecurrence(RecurrenceKind.YEARLY, month = 4, day = 31),
            TaskRecurrence(RecurrenceKind.MONTHLY, day = 5, dueWithinDays = 366),
        ).forEach { assertNotNull(it.validationError(), "expected $it to be rejected") }
    }
}
