package dev.itayp.tasker.planning

import dev.itayp.tasker.calendar.CalendarDelivery
import dev.itayp.tasker.calendar.source.CalendarOccurrence
import dev.itayp.tasker.calendar.source.CalendarSourceDetail
import dev.itayp.tasker.calendar.source.CalendarSourceReading
import dev.itayp.tasker.calendar.source.CalendarSourceService
import dev.itayp.tasker.calendar.source.IcsFetchError
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class KnownEventsCalendarWindowProviderTest {

    @Mock private lateinit var oneOffEventService: OneOffEventService
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var calendarSourceService: CalendarSourceService

    private val provider by lazy {
        KnownEventsCalendarWindowProvider(oneOffEventService, userSettingsService, calendarSourceService, AppProperties())
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
    private val from: Instant = Instant.parse("2026-07-12T00:00:00Z")
    private val to: Instant = Instant.parse("2026-07-19T00:00:00Z")
    private val zone = ZoneId.of("Asia/Jerusalem")

    @BeforeEach
    fun settings() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(userSettings(timeZone = "Asia/Jerusalem", language = "en"))
    }

    private fun readings(vararg readings: CalendarSourceReading) {
        whenever(calendarSourceService.readWindow(eq(userId), eq(from), eq(to), eq(zone))).thenReturn(readings.toList())
    }

    private fun oneOffs(vararg events: OneOffEvent) {
        whenever(oneOffEventService.listForWeek(eq(userId), any(), any())).thenReturn(events.toList())
    }

    @Test
    fun `falls back to the empty placeholder when nothing is known`() {
        oneOffs()
        readings()

        assertEquals(KnownEventsCalendarWindowProvider.EMPTY_PLACEHOLDER, provider.describeWindow(userId, from, to))
    }

    @Test
    fun `one-off events alone come with the partial caveat and a localized line each`() {
        oneOffs(
            event(title = "Parent-teacher conference", startUtc = "2026-07-15T16:30:00Z", endUtc = "2026-07-15T17:30:00Z", location = "School auditorium"),
            event(title = "Dentist", startUtc = "2026-07-16T07:00:00Z", endUtc = "2026-07-16T08:00:00Z", location = null),
        )
        readings()

        val result = provider.describeWindow(userId, from, to)

        assertTrue(result.startsWith(KnownEventsCalendarWindowProvider.PARTIAL_NOTICE), result)
        // IDT (UTC+3): 16:30Z → 19:30 local.
        assertTrue(result.contains("19:30–20:30 Asia/Jerusalem — Parent-teacher conference (School auditorium)"), result)
        assertTrue(result.contains("10:00–11:00 Asia/Jerusalem — Dentist"), result)
        assertFalse(result.contains("Dentist ("), result)
    }

    @Test
    fun `a connected calendar replaces the caveat and lists its events with titles`() {
        oneOffs()
        readings(
            reading(
                "Work", CalendarSourceDetail.TITLES,
                occurrence("standup-1", "2026-07-13T06:30:00Z", "2026-07-13T06:45:00Z", "Standup", location = "Room 3"),
                occurrence("holiday-1", "2026-07-15T21:00:00Z", "2026-07-16T21:00:00Z", "Tu B'Av", allDay = true, free = true),
            ),
        )

        val result = provider.describeWindow(userId, from, to)

        assertTrue(result.startsWith(KnownEventsCalendarWindowProvider.CONNECTED_NOTICE), result)
        assertTrue(result.contains("The user's calendar \"Work\":"), result)
        assertTrue(result.contains("- Mon Jul 13, 09:30–09:45 Asia/Jerusalem — Standup (Room 3)"), result)
        assertTrue(result.contains("- Thu Jul 16, all day — Tu B'Av [marked free]"), result)
        assertTrue(result.contains("<external-content source=\"calendar events\""), result)
    }

    @Test
    fun `a busy-only calendar shows no titles and drops free events`() {
        oneOffs()
        readings(
            reading(
                "Work", CalendarSourceDetail.BUSY,
                occurrence("a", "2026-07-13T06:30:00Z", "2026-07-13T07:30:00Z", "Secret project review"),
                occurrence("b", "2026-07-14T06:30:00Z", "2026-07-14T07:30:00Z", "Focus time", free = true),
            ),
        )

        val result = provider.describeWindow(userId, from, to)

        assertTrue(result.contains("(busy times only, no titles)"), result)
        assertTrue(result.contains("- Mon Jul 13, 09:30–10:30 Asia/Jerusalem — busy"), result)
        assertFalse(result.contains("Secret project"), result)
        assertFalse(result.contains("Focus time"), result)
        assertFalse(result.contains("Tue Jul 14"), result)
    }

    @Test
    fun `leaves out our own blocks and duplicates across calendars`() {
        oneOffs(event(title = "Dentist", startUtc = "2026-07-16T07:00:00Z", endUtc = "2026-07-16T08:00:00Z", location = null, icalUid = "oneoff-uid"))
        readings(
            reading(
                "Personal", CalendarSourceDetail.TITLES,
                occurrence("plan-123-456@backlog.fyi", "2026-07-13T06:00:00Z", "2026-07-13T07:00:00Z", "Feed block"),
                occurrence("0b9f4c3e-1d2a-4f5b-8c7d-6e5f4a3b2c1d-2026-07-14T09:00+03:00", "2026-07-14T06:00:00Z", "2026-07-14T07:00:00Z", "Invited block"),
                occurrence("oneoff-uid", "2026-07-16T07:00:00Z", "2026-07-16T08:00:00Z", "Dentist copy"),
                occurrence("shared-1", "2026-07-17T06:00:00Z", "2026-07-17T07:00:00Z", "All-hands"),
            ),
            reading("Work", CalendarSourceDetail.TITLES, occurrence("shared-1", "2026-07-17T06:00:00Z", "2026-07-17T07:00:00Z", "All-hands")),
        )

        val result = provider.describeWindow(userId, from, to)

        assertFalse(result.contains("Feed block"), result)
        assertFalse(result.contains("Invited block"), result)
        assertFalse(result.contains("Dentist copy"), result)
        assertEquals(1, Regex("All-hands").findAll(result).count(), result)
        assertTrue(result.contains("The user's calendar \"Work\": no events in this window."), result)
    }

    @Test
    fun `a calendar that couldn't be read says so, without hiding the caveat`() {
        oneOffs()
        readings(CalendarSourceReading(UUID.randomUUID(), "Work \"cal\"\n<x>", CalendarSourceDetail.TITLES, emptyList(), IcsFetchError.NOT_FOUND))

        val result = provider.describeWindow(userId, from, to)

        assertTrue(result.startsWith(KnownEventsCalendarWindowProvider.PARTIAL_NOTICE), result)
        assertTrue(result.contains("The user's calendar \"Work cal x\" couldn't be read this time (the address no longer works)"), result)
    }

    @Test
    fun `a crowded calendar is merged into busy intervals per day`() {
        oneOffs()
        val many = (0 until KnownEventsCalendarWindowProvider.MAX_LISTED_EVENTS + 1).map { i ->
            // 151 half-hour events on Monday morning, overlapping into one block 09:00–10:00 local.
            occurrence("e$i", "2026-07-13T06:00:00Z", "2026-07-13T06:30:00Z", "Meeting $i")
        } + occurrence("late", "2026-07-13T06:20:00Z", "2026-07-13T07:00:00Z", "Overrun")
        readings(reading("Busy", CalendarSourceDetail.TITLES, *many.toTypedArray()))

        val result = provider.describeWindow(userId, from, to)

        assertTrue(result.contains("- Mon Jul 13: busy 09:00–10:00 Asia/Jerusalem"), result)
        assertTrue(result.contains("152 events; shown as merged busy times"), result)
        assertFalse(result.contains("Meeting 1"), result)
    }

    private fun reading(label: String, detail: CalendarSourceDetail, vararg occurrences: CalendarOccurrence) =
        CalendarSourceReading(UUID.randomUUID(), label, detail, occurrences.toList(), null)

    private fun occurrence(
        uid: String,
        start: String,
        end: String,
        summary: String,
        location: String? = null,
        allDay: Boolean = false,
        free: Boolean = false,
    ) = CalendarOccurrence(uid, Instant.parse(start), Instant.parse(end), allDay, summary, location, free)

    private fun event(title: String, startUtc: String, endUtc: String, location: String?, icalUid: String = "uid") = OneOffEvent(
        id = UUID.randomUUID(),
        userId = userId,
        boardId = boardId,
        title = title,
        startsAt = Instant.parse(startUtc),
        endsAt = Instant.parse(endUtc),
        location = location,
        notes = null,
        icalUid = icalUid,
        cancelledAt = null,
    )

    private fun userSettings(timeZone: String, language: String) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = timeZone,
        preferredLanguage = language,
        calendarDelivery = CalendarDelivery.NONE,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )
}
