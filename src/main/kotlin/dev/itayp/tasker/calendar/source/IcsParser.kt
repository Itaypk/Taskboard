package dev.itayp.tasker.calendar.source

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Transp
import org.springframework.stereotype.Component as SpringComponent
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal

/**
 * Parses iCalendar files from other calendars (GitHub issue #347, #413) with ical4j, configured in
 * `ical4j.properties` for real-world feeds (relaxed parsing) and with its online time-zone updates
 * off, so parsing never makes a network call of its own.
 */
@SpringComponent
class IcsParser {

    /** @throws IcsFetchException with [IcsFetchError.PARSE_ERROR] when the file can't be used. */
    fun parse(body: ByteArray): ParsedCalendar {
        // ical4j registers every embedded VTIMEZONE in a JVM-wide pool of zone ids (1500 by
        // default, freed only once the parsed calendar is collected). Cap them per file so one
        // hostile calendar can't drain the pool for everyone.
        if (countOccurrences(body, VTIMEZONE_MARKER) > MAX_TIME_ZONES) throw IcsFetchException(IcsFetchError.PARSE_ERROR)
        val calendar = try {
            CalendarBuilder().build(ByteArrayInputStream(body))
        } catch (_: ParserException) {
            throw IcsFetchException(IcsFetchError.PARSE_ERROR)
        } catch (_: IOException) {
            throw IcsFetchException(IcsFetchError.PARSE_ERROR)
        } catch (_: RuntimeException) {
            // ical4j throws runtime exceptions for some malformed values (dates, rules).
            throw IcsFetchException(IcsFetchError.PARSE_ERROR)
        }
        val events = calendar.getComponents<VEvent>(Component.VEVENT)
        if (events.size > MAX_EVENTS) throw IcsFetchException(IcsFetchError.PARSE_ERROR)
        return ParsedCalendar(calendar, events)
    }

    companion object {
        const val MAX_TIME_ZONES = 25
        const val MAX_EVENTS = 50_000
        private val VTIMEZONE_MARKER = "BEGIN:VTIMEZONE".toByteArray(Charsets.US_ASCII)

        private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
            var count = 0
            var i = 0
            outer@ while (i <= haystack.size - needle.size) {
                for (k in needle.indices) {
                    if (haystack[i + k] != needle[k]) {
                        i++
                        continue@outer
                    }
                }
                count++
                i += needle.size
            }
            return count
        }
    }
}

/** One occurrence of an event, recurrences expanded. */
data class CalendarOccurrence(
    val uid: String?,
    val start: Instant,
    val end: Instant,
    /** An all-day event: [start]/[end] are midnights in the zone the window was expanded in. */
    val allDay: Boolean,
    val summary: String?,
    val location: String?,
    /** `TRANSP:TRANSPARENT`: on the calendar, but not blocking time (holidays, reminders). */
    val free: Boolean,
)

/**
 * A parsed calendar, ready to be expanded over any window. Kept (cached) instead of the raw bytes,
 * since parsing is the expensive part; expansion only walks the window.
 */
class ParsedCalendar internal constructor(
    @Suppress("unused") private val calendar: Calendar, // keeps ical4j's time-zone registry alive
    private val events: List<VEvent>,
) {

    val eventCount: Int get() = events.size

    /**
     * Occurrences overlapping [from, to), sorted by start. Floating times and all-day dates are
     * read in [zone], the user's. `STATUS:CANCELLED` events and instances are left out, and an
     * instance moved by a `RECURRENCE-ID` override appears only at its new time.
     */
    fun occurrences(from: Instant, to: Instant, zone: ZoneId, limit: Int = MAX_OCCURRENCES): List<CalendarOccurrence> {
        val window = Period(from.atZone(zone), to.atZone(zone))
        val byUid = events.groupBy { it.uid.map { u -> u.value }.orElse(null) }
        val result = mutableListOf<CalendarOccurrence>()
        for ((uid, group) in byUid) {
            val (overrides, masters) = group.partition { recurrenceId(it) != null }
            val overrideSet = overrides.toSet()
            // Instances some override replaces (or cancels), keyed by their original start.
            val overridden = overrides.mapNotNull { recurrenceId(it)?.let { rid -> toInstant(rid, zone) } }.toSet()
            for (event in masters + overrides) {
                if (isCancelled(event)) continue
                val isOverride = event in overrideSet
                val periods = try {
                    event.calculateRecurrenceSet<Temporal>(window)
                } catch (_: RuntimeException) {
                    continue // one broken rule shouldn't hide the rest of the calendar
                }
                for (period in periods) {
                    val start = toInstant(period.start, zone)
                    if (!isOverride && uid != null && start in overridden) continue
                    val end = period.end?.let { toInstant(it, zone) } ?: start
                    val overlaps = start.isBefore(to) && (end.isAfter(from) || (!end.isAfter(start) && !start.isBefore(from)))
                    if (!overlaps) continue
                    result += CalendarOccurrence(
                        uid = uid,
                        start = start,
                        end = if (end.isBefore(start)) start else end,
                        allDay = period.start is LocalDate,
                        summary = text(event.propertyList.getProperty<Summary>(Property.SUMMARY).map { it.value }.orElse(null)),
                        location = text(event.propertyList.getProperty<Location>(Property.LOCATION).map { it.value }.orElse(null)),
                        free = event.propertyList.getProperty<Transp>(Property.TRANSP)
                            .map { it.value.equals(Transp.VALUE_TRANSPARENT, ignoreCase = true) }.orElse(false),
                    )
                    if (result.size >= limit) return result.sortedBy { it.start }
                }
            }
        }
        return result.sortedBy { it.start }
    }

    private fun recurrenceId(event: VEvent): Temporal? =
        event.propertyList.getProperty<RecurrenceId<Temporal>>(Property.RECURRENCE_ID).map { it.date }.orElse(null)

    private fun isCancelled(event: VEvent): Boolean =
        event.propertyList.getProperty<Status>(Property.STATUS)
            .map { it.value.equals(Status.VALUE_CANCELLED, ignoreCase = true) }.orElse(false)

    companion object {
        const val MAX_OCCURRENCES = 2_000
        private const val MAX_TEXT = 200

        private fun text(value: String?): String? =
            value?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

        internal fun toInstant(t: Temporal, zone: ZoneId): Instant = when (t) {
            is Instant -> t
            is ZonedDateTime -> t.toInstant()
            is OffsetDateTime -> t.toInstant()
            is LocalDateTime -> t.atZone(zone).toInstant()
            is LocalDate -> t.atStartOfDay(zone).toInstant()
            else -> Instant.from(t)
        }
    }
}
