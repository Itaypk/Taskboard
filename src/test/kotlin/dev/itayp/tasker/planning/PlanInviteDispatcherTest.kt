package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.invitation.CalendarEvent
import dev.itayp.tasker.channel.email.invitation.CalendarInvitationComposer
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PlanInviteDispatcherTest {

    @Mock lateinit var composer: CalendarInvitationComposer

    private val dispatcher by lazy { PlanInviteDispatcher(composer) }

    private val userEmail = "alice@example.com"
    private val organizerEmail = "noreply@backlog.fyi"
    private val organizerName = "Backlog.fyi"
    private val taskId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val locale = java.util.Locale.ENGLISH

    // ── Slot dispatch ────────────────────────────────────────────────────────

    @Test
    fun `sends one invite per slot`() {
        val plan = plan(
            task(taskId, "Write spec",
                slot("2026-05-11T09:00:00+02:00", "2026-05-11T11:00:00+02:00"),
                slot("2026-05-13T14:00:00+02:00", "2026-05-13T16:00:00+02:00"),
            ),
        )

        dispatcher.dispatch(userEmail, organizerEmail, organizerName, plan, locale)

        verify(composer, times(2)).sendInvitation(any(), any())
    }

    @Test
    fun `builds CalendarEvent with correct to address and organizer`() {
        val plan = plan(task(taskId, "Write spec", slot("2026-05-11T09:00:00+02:00", "2026-05-11T11:00:00+02:00")))

        dispatcher.dispatch(userEmail, organizerEmail, organizerName, plan, locale)

        val toCaptor = argumentCaptor<List<String>>()
        val eventCaptor = argumentCaptor<CalendarEvent>()
        verify(composer).sendInvitation(toCaptor.capture(), eventCaptor.capture())

        assertEquals(listOf(userEmail), toCaptor.firstValue)
        val event = eventCaptor.firstValue
        assertEquals("Write spec", event.title)
        assertEquals(organizerEmail, event.organizerEmail)
        assertEquals(organizerName, event.organizerName)
        assertEquals(listOf(userEmail), event.attendeeEmails)
    }

    @Test
    fun `uid is stable and derived from taskId and slot start`() {
        val startIso = "2026-05-11T09:00:00+02:00"
        val plan = plan(task(taskId, "Write spec", slot(startIso, "2026-05-11T11:00:00+02:00")))

        dispatcher.dispatch(userEmail, organizerEmail, organizerName, plan, locale)

        val eventCaptor = argumentCaptor<CalendarEvent>()
        verify(composer).sendInvitation(any(), eventCaptor.capture())
        assertEquals("$taskId-$startIso", eventCaptor.firstValue.uid)
    }

    @Test
    fun `sends nothing when all tasks have no slots`() {
        val plan = plan(task(taskId, "Empty task"))

        dispatcher.dispatch(userEmail, organizerEmail, organizerName, plan, locale)

        verify(composer, never()).sendInvitation(any(), any())
    }

    @Test
    fun `continues sending remaining slots when one slot fails`() {
        val plan = plan(
            task(taskId, "Write spec",
                slot("2026-05-11T09:00:00+02:00", "2026-05-11T11:00:00+02:00"),
                slot("2026-05-13T14:00:00+02:00", "2026-05-13T16:00:00+02:00"),
            ),
        )
        doThrow(RuntimeException("SMTP down"))
            .doNothing()
            .`when`(composer).sendInvitation(any(), any())

        dispatcher.dispatch(userEmail, organizerEmail, organizerName, plan, locale)

        verify(composer, times(2)).sendInvitation(any(), any())
    }

    @Test
    fun `parses ISO-8601 offset datetime into correct start and end`() {
        val plan = plan(task(taskId, "Deep work", slot("2026-05-11T09:00:00+02:00", "2026-05-11T11:00:00+02:00")))

        dispatcher.dispatch(userEmail, organizerEmail, organizerName, plan, locale)

        val eventCaptor = argumentCaptor<CalendarEvent>()
        verify(composer).sendInvitation(any(), eventCaptor.capture())
        val event = eventCaptor.firstValue
        assertTrue(event.start.toInstant().epochSecond > 0)
        assertTrue(event.end.isAfter(event.start))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun plan(vararg tasks: AgreedPlanTask) = AgreedPlan(tasks.toList(), summary = "Test summary")

    private fun task(id: UUID, title: String, vararg slots: AgreedTimeSlot) =
        AgreedPlanTask(taskId = id, title = title, slots = slots.toList())

    private fun slot(start: String, end: String) = AgreedTimeSlot(startIso = start, endIso = end)
}
