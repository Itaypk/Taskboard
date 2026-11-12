package dev.itayp.tasker.calendar

/**
 * How agreed time blocks (and one-off events) reach the user's calendar. One choice, not two
 * toggles: invitations and the subscribed calendar together would show every block twice.
 */
enum class CalendarDelivery {
    /** Blocks stay in the app (and in reminders). */
    NONE,

    /** Emailed iTIP invitations (`CalendarInvitationComposer`); needs a verified email. */
    EMAIL,

    /** The user's secret subscription feed (`CalendarFeedController`). */
    FEED;

    companion object {
        /** Lenient parse for stored and imported values: anything unknown is [NONE]. */
        fun fromName(name: String?): CalendarDelivery =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}
