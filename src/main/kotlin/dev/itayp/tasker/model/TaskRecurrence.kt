package dev.itayp.tasker.model

import java.time.Month

/**
 * How a recurring task comes back. Interval kinds restart the clock at completion (maintenance:
 * "6 months after the last visit"); day-of kinds follow the calendar, so paying rent late doesn't
 * move next month's rent. See `docs/RECURRING-TASKS.md`.
 */
enum class RecurrenceKind(val completionAnchored: Boolean) {
    EVERY_N_DAYS(true),
    EVERY_N_MONTHS(true),
    WEEKLY(false),
    MONTHLY(false),
    YEARLY(false);

    companion object {
        val allowedValues: List<String> = entries.map { it.name }

        fun parse(raw: String): RecurrenceKind? = entries.find { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * A recurrence rule. Which of [every] / [day] / [month] is meaningful depends on [kind]; [validate]
 * enforces the combination, and fields that don't belong to the kind must be null so a stored rule
 * never carries stale values from a previous kind.
 */
data class TaskRecurrence(
    val kind: RecurrenceKind,
    val every: Int? = null,
    /** ISO weekday (1 = Monday) for [RecurrenceKind.WEEKLY]; day of month otherwise. */
    val day: Int? = null,
    val month: Int? = null,
    /** Days after each occurrence the task is due; null = no deadline. */
    val dueWithinDays: Int? = null,
) {
    /**
     * Returns null when valid, otherwise a sentence naming the problem and the allowed values —
     * written so an external-API caller (often a model) can correct itself.
     */
    fun validationError(): String? {
        dueWithinDays?.let {
            if (it !in 0..MAX_DUE_WITHIN_DAYS) return "'dueWithinDays' must be between 0 and $MAX_DUE_WITHIN_DAYS."
        }
        return when (kind) {
            RecurrenceKind.EVERY_N_DAYS -> onlyEvery(1..MAX_EVERY_DAYS)
            RecurrenceKind.EVERY_N_MONTHS -> onlyEvery(1..MAX_EVERY_MONTHS)
            RecurrenceKind.WEEKLY -> when {
                every != null || month != null -> "WEEKLY takes only 'day'; 'every' and 'month' must be null."
                day == null || day !in 1..7 -> "WEEKLY requires 'day' between 1 (Monday) and 7 (Sunday)."
                else -> null
            }
            RecurrenceKind.MONTHLY -> when {
                every != null || month != null -> "MONTHLY takes only 'day'; 'every' and 'month' must be null."
                day == null || day !in 1..31 -> "MONTHLY requires 'day' between 1 and 31 (31 means the last day of the month)."
                else -> null
            }
            RecurrenceKind.YEARLY -> when {
                every != null -> "YEARLY takes 'month' and 'day'; 'every' must be null."
                month == null || month !in 1..12 -> "YEARLY requires 'month' between 1 and 12."
                day == null || day !in 1..Month.of(month).maxLength() ->
                    "YEARLY requires 'day' between 1 and ${Month.of(month).maxLength()} for month $month."
                else -> null
            }
        }
    }

    private fun onlyEvery(range: IntRange): String? = when {
        day != null || month != null -> "$kind takes only 'every'; 'day' and 'month' must be null."
        every == null || every !in range -> "$kind requires 'every' between ${range.first} and ${range.last}."
        else -> null
    }

    companion object {
        const val MAX_EVERY_DAYS = 3650
        const val MAX_EVERY_MONTHS = 120
        const val MAX_DUE_WITHIN_DAYS = 365
    }
}
