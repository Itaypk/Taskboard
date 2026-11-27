package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.prompt.ExternalContent
import dev.itayp.tasker.calendar.source.CalendarOccurrence
import dev.itayp.tasker.calendar.source.CalendarSourceDetail
import dev.itayp.tasker.calendar.source.CalendarSourceReading
import dev.itayp.tasker.calendar.source.CalendarSourceService
import dev.itayp.tasker.calendar.source.IcsFetchError
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Returns a human-readable description of the user's calendar across the planning window, for the
 * planning prompt.
 */
interface CalendarWindowProvider {
    fun describeWindow(userId: UUID, from: Instant, to: Instant): String
}

/**
 * What the planner knows of the user's week: one-off events captured through `/add`, plus the
 * events of the calendars the user connected by their private ICS address (GitHub issue #347).
 *
 * Without a connected calendar this is a partial view, and [PARTIAL_NOTICE] says so. With one, the
 * events come per calendar, at the detail the user chose for it, and a calendar that couldn't be
 * read gets its own line, so the assistant can say that clashes with it weren't checked (without
 * questioning the user about their commitments: planning goes on as it did before #347).
 *
 * Our own blocks are left out of connected calendars, or the planner would treat last week's plan
 * as fixed meetings: events from our subscription feed (UIDs ending `@<our host>`), emailed
 * invitations (`<taskId>-<start>`) and one-off events already listed from the database.
 */
@Component
class KnownEventsCalendarWindowProvider(
    private val oneOffEventService: OneOffEventService,
    private val userSettingsService: UserSettingsService,
    private val calendarSourceService: CalendarSourceService,
    appProperties: AppProperties,
) : CalendarWindowProvider {

    private val ownUidSuffix = "@" + (runCatching { URI(appProperties.baseUrl).host }.getOrNull() ?: "localhost")

    override fun describeWindow(userId: UUID, from: Instant, to: Instant): String {
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val locale = runCatching { Locale.forLanguageTag(settings.preferredLanguage) }.getOrDefault(Locale.ENGLISH)
        val format = LineFormat(zone, locale)

        val oneOffs = oneOffEventService.listForWeek(userId, from, to)
        val readings = calendarSourceService.readWindow(userId, from, to, zone)
        if (oneOffs.isEmpty() && readings.isEmpty()) return EMPTY_PLACEHOLDER

        val ownOneOffUids = oneOffs.map { it.icalUid }.toSet()
        val seen = mutableSetOf<Pair<String, Instant>>()
        val connected = readings.any { it.error == null }

        return buildString {
            append(if (connected) CONNECTED_NOTICE else PARTIAL_NOTICE)
            if (oneOffs.isNotEmpty()) {
                append("\n\nEvents the user added through this app:\n")
                append(ExternalContent.wrap("calendar events", oneOffs.joinToString("\n") { format.oneOff(it) }))
            }
            for (reading in readings) {
                val name = sanitizeLabel(reading.label)
                if (reading.error != null) {
                    append("\n\nThe user's calendar \"$name\" couldn't be read this time (${describe(reading.error)}), ")
                    append("so its events are missing from this list. Plan with what is known; mention this once, briefly, ")
                    append("so the user knows clashes with that calendar weren't checked, without asking about their commitments.")
                    continue
                }
                val events = reading.occurrences.filter { occurrence ->
                    !isOwn(occurrence, ownOneOffUids) &&
                        (occurrence.uid == null || seen.add(occurrence.uid to occurrence.start))
                }
                append("\n\n")
                append(renderCalendar(name, reading.detail, events, format))
            }
        }
    }

    private fun renderCalendar(name: String, detail: CalendarSourceDetail, events: List<CalendarOccurrence>, format: LineFormat): String {
        val titles = detail == CalendarSourceDetail.TITLES
        // Busy-only calendars show neither titles nor events marked free (which say nothing without a title).
        val shown = if (titles) events else events.filterNot { it.free }
        val header = if (titles) "The user's calendar \"$name\":" else "The user's calendar \"$name\" (busy times only, no titles):"
        if (shown.isEmpty()) return "$header no events in this window."
        val lines = if (shown.size > MAX_LISTED_EVENTS) {
            format.mergedBusy(shown.filterNot { it.free }) +
                "\n(${shown.size} events; shown as merged busy times because there are too many to list)"
        } else {
            shown.joinToString("\n") { format.occurrence(it, titles) }
        }
        return header + "\n" + if (titles) ExternalContent.wrap("calendar events", lines) else lines
    }

    private fun isOwn(occurrence: CalendarOccurrence, ownOneOffUids: Set<String>): Boolean {
        val uid = occurrence.uid ?: return false
        return uid.endsWith(ownUidSuffix) || uid in ownOneOffUids || INVITATION_UID.matches(uid)
    }

    private fun describe(error: IcsFetchError): String = when (error) {
        IcsFetchError.NOT_FOUND -> "the address no longer works"
        IcsFetchError.UNAUTHORIZED -> "access was refused"
        IcsFetchError.TIMEOUT -> "it took too long"
        IcsFetchError.TOO_LARGE -> "it is too large"
        else -> "it couldn't be fetched or understood"
    }

    /** How events are written: in the user's zone and language, one per line. */
    private class LineFormat(private val zone: ZoneId, locale: Locale) {
        private val dateTime = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", locale)
        private val date = DateTimeFormatter.ofPattern("EEE MMM d", locale)
        private val time = DateTimeFormatter.ofPattern("HH:mm", locale)

        fun oneOff(event: OneOffEvent): String {
            val start = event.startsAt.atZone(zone)
            val end = event.endsAt.atZone(zone).toLocalTime()
            val locationSuffix = event.location?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            return "- ${dateTime.format(start)}–$end ${zone.id} — ${event.title}$locationSuffix"
        }

        fun occurrence(event: CalendarOccurrence, titles: Boolean): String {
            val what = if (titles) {
                listOfNotNull(event.summary ?: "(no title)", event.location?.let { "($it)" }).joinToString(" ")
            } else {
                "busy"
            }
            val freeNote = if (event.free) " [marked free]" else ""
            return "- ${span(event)} — $what$freeNote"
        }

        private fun span(event: CalendarOccurrence): String {
            val start = event.start.atZone(zone)
            val end = event.end.atZone(zone)
            if (event.allDay) {
                // All-day ends are exclusive midnights.
                val last = end.toLocalDate().minusDays(1).let { if (it.isBefore(start.toLocalDate())) start.toLocalDate() else it }
                return if (last == start.toLocalDate()) "${date.format(start)}, all day" else "${date.format(start)} – ${date.format(last)}, all day"
            }
            return if (end.toLocalDate() == start.toLocalDate()) {
                "${dateTime.format(start)}–${time.format(end)} ${zone.id}"
            } else {
                "${dateTime.format(start)} – ${dateTime.format(end)} ${zone.id}"
            }
        }

        /** Per day, the busy intervals with overlaps merged: "Mon Nov 30: 09:00–12:30, 14:00–15:00". */
        fun mergedBusy(events: List<CalendarOccurrence>): String {
            val byDay = sortedMapOf<LocalDate, MutableList<Pair<ZonedDateTime, ZonedDateTime>>>()
            for (event in events) {
                // Split multi-day events at midnight, so each day reads on its own.
                var cursor = event.start.atZone(zone)
                val end = event.end.atZone(zone)
                while (cursor.isBefore(end)) {
                    val to = minOf(end, cursor.toLocalDate().plusDays(1).atStartOfDay(zone))
                    byDay.getOrPut(cursor.toLocalDate()) { mutableListOf() } += cursor to to
                    cursor = to
                }
            }
            return byDay.entries.joinToString("\n") { (day, intervals) ->
                val merged = mutableListOf<Pair<ZonedDateTime, ZonedDateTime>>()
                for ((s, e) in intervals.sortedBy { it.first }) {
                    val last = merged.lastOrNull()
                    if (last != null && !s.isAfter(last.second)) merged[merged.lastIndex] = last.first to maxOf(last.second, e) else merged += s to e
                }
                "- ${date.format(day)}: busy " + merged.joinToString(", ") { (s, e) ->
                    val endText = if (e.toLocalDate() != day) "24:00" else time.format(e)
                    "${time.format(s)}–$endText"
                } + " ${zone.id}"
            }
        }
    }

    companion object {
        const val EMPTY_PLACEHOLDER =
            "User has not connected their calendar; assume no fixed commitments are known."
        const val PARTIAL_NOTICE =
            "Known events on the user's calendar (partial — only events captured through " +
                "this app; the user's other calendar entries are not visible, so don't assume " +
                "the rest of the week is free):"
        const val CONNECTED_NOTICE =
            "Events on the user's calendars this week, read from the calendars they connected and " +
                "the events they added through this app. Treat timed events as fixed commitments and " +
                "don't propose blocks that overlap them. Events marked free, and all-day events such as " +
                "holidays, are context rather than blocked time: mention them where they matter (a short " +
                "week, a day off) and ask instead of assuming the user is unavailable."

        /** Above this many events in a calendar's window, busy times are merged instead of listed. */
        const val MAX_LISTED_EVENTS = 150

        /** UIDs of the invitations we email: `<task uuid>-<ISO start>` (see PlanInviteDispatcher). */
        private val INVITATION_UID =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-\\d{4}-\\d{2}-\\d{2}T.*")

        /** The user's own label, made safe to quote in the prompt. */
        fun sanitizeLabel(label: String): String =
            label.replace(Regex("[\\p{Cntrl}\"<>]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "calendar" }
    }
}
