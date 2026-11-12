package dev.itayp.tasker.calendar

import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CalendarFeedRendererTest {

    private val renderer = CalendarFeedRenderer(AppProperties(baseUrl = "https://app.example.com", name = "Planner"))
    private val stamp = Instant.parse("2026-11-10T08:00:00Z")
    private val taskId = UUID.fromString("0193a6c0-0000-7000-8000-000000000001")
    private val eventId = UUID.fromString("0193a6c0-0000-7000-8000-000000000002")

    private val task = AgreedPlanTask(
        taskId = taskId,
        title = "Write report, part 1; draft",
        slots = listOf(AgreedTimeSlot("2026-11-12T09:00:00+02:00", "2026-11-12T10:30:00+02:00")),
        notes = "Outline first",
    )
    private val event = OneOffEvent(
        id = eventId,
        userId = UUID.randomUUID(),
        boardId = UUID.randomUUID(),
        title = "Dentist",
        startsAt = Instant.parse("2026-11-13T14:00:00Z"),
        endsAt = Instant.parse("2026-11-13T15:00:00Z"),
        location = "Main St 1",
        notes = null,
        icalUid = "invite-uid",
        cancelledAt = null,
    )

    private fun render(content: CalendarContent = CalendarContent(listOf(task), listOf(event))) =
        renderer.render(content, stamp)

    @Test
    fun `publishes a named calendar with refresh hints`() {
        val ics = render()

        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(ics.contains("METHOD:PUBLISH\r\n"))
        assertTrue(ics.contains("X-WR-CALNAME:Planner\r\n"))
        assertTrue(ics.contains("REFRESH-INTERVAL;VALUE=DURATION:PT1H\r\n"))
        assertTrue(ics.contains("X-PUBLISHED-TTL:PT1H\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun `plan slots become events in UTC with escaped text`() {
        val ics = render()

        assertTrue(ics.contains("UID:plan-$taskId-1794466800@app.example.com\r\n"), ics)
        assertTrue(ics.contains("DTSTART:20261112T070000Z\r\n"))
        assertTrue(ics.contains("DTEND:20261112T083000Z\r\n"))
        assertTrue(ics.contains("SUMMARY:Write report\\, part 1\\; draft\r\n"))
        assertTrue(ics.contains("DESCRIPTION:Outline first\r\n"))
        assertTrue(ics.contains("DTSTAMP:20261110T080000Z\r\n"))
    }

    @Test
    fun `one-off events use their own UID namespace, never the invitation UID`() {
        val ics = render()

        assertTrue(ics.contains("UID:event-$eventId@app.example.com\r\n"))
        assertFalse(ics.contains("invite-uid"))
        assertTrue(ics.contains("LOCATION:Main St 1\r\n"))
    }

    @Test
    fun `no alarms, since subscribed calendars mostly ignore them`() {
        assertFalse(render().contains("VALARM"))
    }

    @Test
    fun `same content renders identically, so the body hash works as an ETag`() {
        assertEquals(render(), render())
    }

    @Test
    fun `an empty calendar is still a valid calendar`() {
        val ics = render(CalendarContent(emptyList(), emptyList()))

        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }
}
