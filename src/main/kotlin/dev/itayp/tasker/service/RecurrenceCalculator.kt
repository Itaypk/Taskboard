package dev.itayp.tasker.service

import dev.itayp.tasker.model.RecurrenceKind
import dev.itayp.tasker.model.TaskRecurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Pure date math for recurring tasks. Assumes a rule that passed [TaskRecurrence.validationError]. */
object RecurrenceCalculator {

    /**
     * The date a task reappears after being completed on [completedOn].
     *
     * Calendar rules take the first match strictly after `max(currentOccurrence, completedOn)`:
     * finishing early still counts toward the pending occurrence, and finishing late merges any
     * occurrences that passed in the meantime into this completion.
     */
    fun nextOccurrence(rule: TaskRecurrence, currentOccurrence: LocalDate?, completedOn: LocalDate): LocalDate =
        when (rule.kind) {
            RecurrenceKind.EVERY_N_DAYS -> completedOn.plusDays(rule.every!!.toLong())
            // plusMonths clamps month-end: Jan 31 + 1 month = Feb 28/29.
            RecurrenceKind.EVERY_N_MONTHS -> completedOn.plusMonths(rule.every!!.toLong())
            else -> {
                val anchor = if (currentOccurrence != null && currentOccurrence.isAfter(completedOn)) {
                    currentOccurrence
                } else {
                    completedOn
                }
                firstMatchOnOrAfter(rule, anchor.plusDays(1))
            }
        }

    /**
     * Default first occurrence for a rule set on [today]: today itself for interval rules (the task
     * is actionable now), the next matching date for calendar rules — so "yearly on 03-14" created in
     * September doesn't show up immediately.
     */
    fun firstOccurrence(rule: TaskRecurrence, today: LocalDate): LocalDate =
        if (rule.kind.completionAnchored) today else firstMatchOnOrAfter(rule, today)

    fun deadlineFor(rule: TaskRecurrence, occurrence: LocalDate): LocalDate? =
        rule.dueWithinDays?.let { occurrence.plusDays(it.toLong()) }

    private fun firstMatchOnOrAfter(rule: TaskRecurrence, date: LocalDate): LocalDate = when (rule.kind) {
        RecurrenceKind.WEEKLY -> date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(rule.day!!)))
        RecurrenceKind.MONTHLY -> {
            val thisMonth = clampedDay(YearMonth.from(date), rule.day!!)
            if (thisMonth.isBefore(date)) clampedDay(YearMonth.from(date).plusMonths(1), rule.day) else thisMonth
        }
        RecurrenceKind.YEARLY -> {
            val thisYear = clampedDay(YearMonth.of(date.year, rule.month!!), rule.day!!)
            if (thisYear.isBefore(date)) clampedDay(YearMonth.of(date.year + 1, rule.month), rule.day) else thisYear
        }
        RecurrenceKind.EVERY_N_DAYS, RecurrenceKind.EVERY_N_MONTHS ->
            throw IllegalArgumentException("${rule.kind} is completion-anchored and has no calendar match")
    }

    /** Day 31 means "last day of the month"; Feb 29 lands on Feb 28 in non-leap years. */
    private fun clampedDay(month: YearMonth, day: Int): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))
}
