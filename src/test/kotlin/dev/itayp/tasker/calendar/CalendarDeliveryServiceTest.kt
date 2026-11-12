package dev.itayp.tasker.calendar

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.invitation.ICalSequence
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.oneoff.OneOffEventInviteDispatcher
import dev.itayp.tasker.planning.InviteDeliveryResolver
import dev.itayp.tasker.planning.PlanInviteDispatcher
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class CalendarDeliveryServiceTest {

    private val userSettingsService: UserSettingsService = mock()
    private val inviteDeliveryResolver: InviteDeliveryResolver = mock()
    private val calendarFeedService: CalendarFeedService = mock()
    private val calendarFeedContent: CalendarFeedContent = mock()
    private val planInviteDispatcher: PlanInviteDispatcher = mock()
    private val oneOffEventInviteDispatcher: OneOffEventInviteDispatcher = mock()
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = CalendarDeliveryService(
        userSettingsService, inviteDeliveryResolver, calendarFeedService, calendarFeedContent,
        planInviteDispatcher, oneOffEventInviteDispatcher,
        EmailProperties(enabled = true, scheduling = EmailProperties.SenderConfig(from = "plans@example.com")),
        ICalSequence(clock), clock,
    )

    private val userId = UUID.randomUUID()
    private val email = InviteDeliveryResolver.EmailContext("alice@example.com", Locale.ENGLISH)
    private val upcoming = AgreedPlanTask(
        UUID.randomUUID(), "Write",
        listOf(
            AgreedTimeSlot("2026-11-12T11:00:00Z", "2026-11-12T13:00:00Z"), // under way: left alone
            AgreedTimeSlot("2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z"),
        ),
    )

    private fun currently(delivery: CalendarDelivery, emailTarget: InviteDeliveryResolver.EmailContext? = email) {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId, displayName = null, contextBlock = null, timeZone = "UTC",
                preferredLanguage = "en", calendarDelivery = delivery, gender = null, agentDescription = null,
                planningCron = null, weekStartDay = null, autoArchiveDays = null,
            ),
        )
        whenever(inviteDeliveryResolver.resolveEmailTarget(userId)).thenReturn(emailTarget)
        whenever(calendarFeedContent.collect(userId, now, now)).thenReturn(CalendarContent(listOf(upcoming), emptyList()))
    }

    @Test
    fun `invitations to feed cancels the upcoming invitations`() {
        currently(CalendarDelivery.EMAIL)

        service.change(userId, CalendarDelivery.FEED)

        verify(userSettingsService).setCalendarDelivery(userId, CalendarDelivery.FEED)
        verify(calendarFeedService).getOrCreateLink(userId)
        val cancelled = argumentCaptor<List<AgreedPlanTask>>()
        verify(planInviteDispatcher).dispatchCancellations(
            eq("alice@example.com"), any(), any(), cancelled.capture(), eq(Locale.ENGLISH), any(),
        )
        assertEquals(listOf("2026-11-13T09:00:00Z"), cancelled.firstValue.single().slots.map { it.startIso })
        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `feed to invitations sends invitations for upcoming blocks`() {
        currently(CalendarDelivery.FEED)

        service.change(userId, CalendarDelivery.EMAIL)

        verify(planInviteDispatcher).dispatch(eq("alice@example.com"), any(), any(), any(), eq(Locale.ENGLISH), any())
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `invitations to off leaves the calendar alone`() {
        currently(CalendarDelivery.EMAIL)

        service.change(userId, CalendarDelivery.NONE)

        verify(userSettingsService).setCalendarDelivery(userId, CalendarDelivery.NONE)
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `invitations without a verified email are refused and nothing changes`() {
        currently(CalendarDelivery.NONE, emailTarget = null)

        assertThrows<CalendarInvitesUnavailableException> { service.change(userId, CalendarDelivery.EMAIL) }

        verify(userSettingsService, never()).setCalendarDelivery(any(), any())
    }

    @Test
    fun `preview counts only blocks that haven't started`() {
        currently(CalendarDelivery.EMAIL)

        assertEquals(CalendarDeliverySwitchPreview(invitationsToCancel = 1, invitationsToSend = 0), service.preview(userId, CalendarDelivery.FEED))
        assertEquals(CalendarDeliverySwitchPreview(0, 0), service.preview(userId, CalendarDelivery.NONE))
    }

    @Test
    fun `choosing the current delivery again is a no-op`() {
        currently(CalendarDelivery.FEED)
        whenever(calendarFeedService.getOrCreateLink(userId)).thenReturn(
            CalendarFeedLink("https://x/calendar/t.ics", "webcal://x/calendar/t.ics", "g", now, null),
        )

        service.change(userId, CalendarDelivery.FEED)

        verify(userSettingsService, never()).setCalendarDelivery(anyOrNull(), anyOrNull())
    }
}
