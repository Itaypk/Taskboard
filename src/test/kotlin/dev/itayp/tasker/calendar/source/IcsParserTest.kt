package dev.itayp.tasker.calendar.source

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcsParserTest {

    private val parser = IcsParser()
    private val jerusalem = ZoneId.of("Asia/Jerusalem")

    // Mon 30 Nov 2026 – Mon 7 Dec 2026, in the user's zone.
    private val from = ZonedDateTime.of(2026, 11, 30, 0, 0, 0, 0, jerusalem).toInstant()
    private val to = ZonedDateTime.of(2026, 12, 7, 0, 0, 0, 0, jerusalem).toInstant()

    private fun resource(name: String) = javaClass.getResourceAsStream("/ics/$name")!!.readAllBytes()

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `expands an Outlook calendar with a Windows time zone, exceptions and overrides`() {
        val occurrences = parser.parse(resource("outlook-sample.ics")).occurrences(from, to, jerusalem)

        val standups = occurrences.filter { it.summary?.startsWith("Standup") == true }
        // Weekdays at 09:30 Berlin (08:30Z): Mon 30th, Tue 1st excluded by EXDATE, Wed 2nd moved to
        // 11:00, Thu 3rd cancelled by its override, Fri 4th as usual.
        assertEquals(
            listOf(at("2026-11-30T08:30:00Z"), at("2026-12-02T10:00:00Z"), at("2026-12-04T08:30:00Z")),
            standups.map { it.start },
        )
        assertEquals("Standup (moved)", standups[1].summary)
        assertEquals(at("2026-12-02T10:15:00Z"), standups[1].end)
        assertTrue(standups.all { it.uid == "040000008200E00074C5B7101A82E00800000000standup" })

        assertTrue(occurrences.none { it.summary == "Cancelled review" })
        assertTrue(occurrences.none { it.summary == "Next month" })
    }

    @Test
    fun `reads text, all-day, free and floating events`() {
        val occurrences = parser.parse(resource("outlook-sample.ics")).occurrences(from, to, jerusalem).associateBy { it.uid }

        val call = occurrences.getValue("utc-1")
        assertEquals("Call with, Dana", call.summary)
        assertEquals("Room 3", call.location)
        assertEquals(at("2026-12-03T15:00:00Z"), call.start)
        assertFalse(call.free)

        val holiday = occurrences.getValue("allday-1")
        assertTrue(holiday.allDay)
        assertTrue(holiday.free)
        assertEquals(ZonedDateTime.of(2026, 12, 5, 0, 0, 0, 0, jerusalem).toInstant(), holiday.start)
        assertEquals(ZonedDateTime.of(2026, 12, 6, 0, 0, 0, 0, jerusalem).toInstant(), holiday.end)

        // Floating 08:00 is read in the user's zone; the end comes from DURATION.
        val floating = occurrences.getValue("floating-1")
        assertEquals(ZonedDateTime.of(2026, 12, 4, 8, 0, 0, 0, jerusalem).toInstant(), floating.start)
        assertEquals(ZonedDateTime.of(2026, 12, 4, 10, 0, 0, 0, jerusalem).toInstant(), floating.end)
    }

    @Test
    fun `expands a Google calendar`() {
        val occurrences = parser.parse(resource("google-sample.ics")).occurrences(from, to, jerusalem)

        val climbing = occurrences.single { it.summary == "Climbing" }
        assertEquals(at("2026-12-01T17:00:00Z"), climbing.start)
        assertEquals("Gym", climbing.location)

        // A two-day all-day event that started before the window still overlaps it.
        val ooo = occurrences.single { it.summary == "Out of office" }
        assertTrue(ooo.allDay)
        assertFalse(ooo.free)
    }

    @Test
    fun `an event that ended before the window or starts after it is left out`() {
        val narrowFrom = at("2026-12-03T15:00:00Z")
        val narrowTo = at("2026-12-03T15:30:00Z")

        val occurrences = parser.parse(resource("outlook-sample.ics")).occurrences(narrowFrom, narrowTo, jerusalem)

        assertEquals(listOf("utc-1"), occurrences.map { it.uid })
    }

    @Test
    fun `caps the number of occurrences`() {
        val occurrences = parser.parse(resource("outlook-sample.ics")).occurrences(from, to, jerusalem, limit = 2)

        assertEquals(2, occurrences.size)
    }

    @Test
    fun `refuses what isn't a usable calendar`() {
        assertEquals(IcsFetchError.PARSE_ERROR, assertFailsWith<IcsFetchException> { parser.parse("BEGIN:VCALENDAR\r\nthis is not".toByteArray()) }.error)
        assertEquals(IcsFetchError.PARSE_ERROR, assertFailsWith<IcsFetchException> { parser.parse("<html></html>".toByteArray()) }.error)
    }

    @Test
    fun `refuses a calendar with an unreasonable number of time zones`() {
        val zone = String(resource("google-sample.ics")).substringAfter("BEGIN:VTIMEZONE").substringBefore("END:VTIMEZONE")
        val body = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n")
            repeat(IcsParser.MAX_TIME_ZONES + 1) { i ->
                append("BEGIN:VTIMEZONE").append(zone.replace("TZID:Asia/Jerusalem", "TZID:Zone$i")).append("END:VTIMEZONE\r\n")
            }
            append("END:VCALENDAR\r\n")
        }

        assertEquals(IcsFetchError.PARSE_ERROR, assertFailsWith<IcsFetchException> { parser.parse(body.toByteArray()) }.error)
    }
}
