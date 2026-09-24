package dev.itayp.tasker.channel.email.invitation

import java.time.ZonedDateTime
import java.util.Locale
import kotlin.uuid.Uuid

data class CalendarEvent(
    val uid: String = Uuid.generateV7().toString(),
    val title: String,
    val description: String? = null,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val location: String? = null,
    val organizerEmail: String,
    val organizerName: String? = null,
    val attendeeEmails: List<String>,
    val rsvp: Boolean = false,
    val locale: Locale = Locale.ENGLISH,
    // iCalendar revision counter; see ICalSequence. No default so every sender picks one deliberately.
    val sequence: Int,
)
