package dev.itayp.tasker.channel.email.invitation

import java.time.ZonedDateTime
import java.util.UUID

data class CalendarEvent(
    val uid: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String? = null,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val location: String? = null,
    val organizerEmail: String,
    val organizerName: String? = null,
    val attendeeEmails: List<String>,
    val rsvp: Boolean = false,
)
