package dev.itayp.tasker.planning

import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class OneOffEventCalendarWindowProviderTest {

    @Mock private lateinit var oneOffEventService: OneOffEventService
    @Mock private lateinit var userSettingsService: UserSettingsService

    private val provider by lazy { OneOffEventCalendarWindowProvider(oneOffEventService, userSettingsService) }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
    private val from: Instant = Instant.parse("2026-07-12T00:00:00Z")
    private val to: Instant = Instant.parse("2026-07-19T00:00:00Z")

    @Test
    fun `falls back to the empty placeholder when there are no events in the window`() {
        whenever(oneOffEventService.listForWeek(eq(userId), any(), any())).thenReturn(emptyList())

        val result = provider.describeWindow(userId, from, to)

        assertEquals(OneOffEventCalendarWindowProvider.EMPTY_PLACEHOLDER, result)
    }

    @Test
    fun `emits the partial caveat and a localised line per event`() {
        whenever(userSettingsService.getOrCreate(userId))
            .thenReturn(userSettings(timeZone = "Asia/Jerusalem", language = "en"))
        whenever(oneOffEventService.listForWeek(eq(userId), any(), any())).thenReturn(
            listOf(
                event(
                    title = "Parent-teacher conference",
                    startUtc = "2026-07-15T16:30:00Z",
                    endUtc = "2026-07-15T17:30:00Z",
                    location = "School auditorium",
                ),
                event(
                    title = "Dentist",
                    startUtc = "2026-07-16T07:00:00Z",
                    endUtc = "2026-07-16T08:00:00Z",
                    location = null,
                ),
            )
        )

        val result = provider.describeWindow(userId, from, to)

        assertTrue(
            result.startsWith(OneOffEventCalendarWindowProvider.PARTIAL_NOTICE),
            "Expected partial notice prefix, got: $result",
        )
        // First event is rendered in IDT (UTC+3): 16:30Z → 19:30 local, 17:30Z → 20:30 local.
        assertTrue(
            result.contains("19:30–20:30 Asia/Jerusalem — Parent-teacher conference (School auditorium)"),
            "Missing first event line, got: $result",
        )
        // Second event has no location — no parenthesised suffix.
        assertTrue(
            result.contains("10:00–11:00 Asia/Jerusalem — Dentist"),
            "Missing second event line, got: $result",
        )
        assertTrue(!result.contains("Dentist ("), "Dentist should have no location suffix")
    }

    private fun event(title: String, startUtc: String, endUtc: String, location: String?) = OneOffEvent(
        id = UUID.randomUUID(),
        userId = userId,
        boardId = boardId,
        title = title,
        startsAt = Instant.parse(startUtc),
        endsAt = Instant.parse(endUtc),
        location = location,
        notes = null,
        icalUid = "uid",
        cancelledAt = null,
    )

    private fun userSettings(timeZone: String, language: String) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = timeZone,
        preferredLanguage = language,
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )
}
