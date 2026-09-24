package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.notification.SlotReminderService
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import dev.itayp.tasker.channel.email.invitation.ICalSequence
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class TaskCompletionCancellationServiceTest {

    @Mock lateinit var plannedTaskService: PlannedTaskService
    @Mock lateinit var slotReminderService: SlotReminderService
    @Mock lateinit var planInviteDispatcher: PlanInviteDispatcher
    @Mock lateinit var inviteDeliveryResolver: InviteDeliveryResolver

    // Fixed "now" = 2026-05-13T08:00:00Z.
    private val clock = Clock.fixed(Instant.parse("2026-05-13T08:00:00Z"), ZoneOffset.UTC)

    private val emailProps = EmailProperties(
        enabled = true,
        scheduling = EmailProperties.SenderConfig(from = "noreply@backlog.fyi", fromName = "Backlog.fyi"),
    )

    private val service by lazy {
        TaskCompletionCancellationService(
            plannedTaskService, slotReminderService, planInviteDispatcher, inviteDeliveryResolver, emailProps, clock,
            ICalSequence(clock),
        )
    }

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()

    private fun task(vararg slots: AgreedTimeSlot) =
        AgreedPlanTask(taskId = taskId, title = "Write spec", notes = "notes", slots = slots.toList())

    @Test
    fun `does nothing when the task isn't planned in the session`() {
        whenever(plannedTaskService.findTaskInSession(userId, sessionId, taskId)).thenReturn(null)

        service.cancelUpcomingSlots(userId, sessionId, taskId)

        verify(slotReminderService, never()).cancelForTask(any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `does nothing when all of the task's slots already started`() {
        val pastSlot = AgreedTimeSlot(startIso = "2026-05-13T07:00:00Z", endIso = "2026-05-13T08:00:00Z")
        whenever(plannedTaskService.findTaskInSession(userId, sessionId, taskId)).thenReturn(task(pastSlot))

        service.cancelUpcomingSlots(userId, sessionId, taskId)

        verify(slotReminderService, never()).cancelForTask(any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `cancels the pending reminder for future slots but not past ones`() {
        val pastSlot = AgreedTimeSlot(startIso = "2026-05-13T07:00:00Z", endIso = "2026-05-13T08:00:00Z")
        val futureSlot = AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")
        whenever(plannedTaskService.findTaskInSession(userId, sessionId, taskId))
            .thenReturn(task(pastSlot, futureSlot))
        whenever(inviteDeliveryResolver.resolveEmailContext(userId)).thenReturn(null)

        service.cancelUpcomingSlots(userId, sessionId, taskId)

        verify(slotReminderService).cancelForTask(sessionId, taskId, listOf(futureSlot))
    }

    @Test
    fun `does not dispatch a cancellation email when the user isn't eligible for invites`() {
        val futureSlot = AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")
        whenever(plannedTaskService.findTaskInSession(userId, sessionId, taskId)).thenReturn(task(futureSlot))
        whenever(inviteDeliveryResolver.resolveEmailContext(userId)).thenReturn(null)

        service.cancelUpcomingSlots(userId, sessionId, taskId)

        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `dispatches a cancellation email covering only the future slots when the user is eligible`() {
        val pastSlot = AgreedTimeSlot(startIso = "2026-05-13T07:00:00Z", endIso = "2026-05-13T08:00:00Z")
        val futureSlot = AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")
        whenever(plannedTaskService.findTaskInSession(userId, sessionId, taskId))
            .thenReturn(task(pastSlot, futureSlot))
        whenever(inviteDeliveryResolver.resolveEmailContext(userId))
            .thenReturn(InviteDeliveryResolver.EmailContext("alice@example.com", Locale.ENGLISH))

        service.cancelUpcomingSlots(userId, sessionId, taskId)

        val tasksCaptor = argumentCaptor<List<AgreedPlanTask>>()
        verify(planInviteDispatcher).dispatchCancellations(
            eq("alice@example.com"), eq("noreply@backlog.fyi"), eq("Backlog.fyi"), tasksCaptor.capture(), eq(Locale.ENGLISH),
            eq(ICalSequence(clock).next()),
        )
        assertEquals(1, tasksCaptor.firstValue.size)
        assertEquals(listOf(futureSlot), tasksCaptor.firstValue.first().slots)
    }
}
