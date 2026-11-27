package dev.itayp.tasker.calendar.source

/** How much of a calendar's events the planning assistant sees. */
enum class CalendarSourceDetail {
    /** Times, titles and locations. The default: titles give the planner context (a holiday, a flight). */
    TITLES,

    /** Times only, for calendars whose event names the user would rather keep from the AI provider. */
    BUSY,
}
