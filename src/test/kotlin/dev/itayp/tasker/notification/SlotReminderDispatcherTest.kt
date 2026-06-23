package dev.itayp.tasker.notification

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.notification.ReminderDeliveryResolver.ReminderContext
import dev.itayp.tasker.service.BacklogTaskService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class SlotReminderDispatcherTest {

    @Mock lateinit var repository: ScheduledNotificationRepository
    @Mock lateinit var deliveryResolver: ReminderDeliveryResolver
    @Mock lateinit var backlogTaskService: BacklogTaskService
    @Mock lateinit var messageSource: MessageSource

    private val meterRegistry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-05-13T09:50:00Z"), ZoneOffset.UTC)
    private val dispatcher by lazy {
        SlotReminderDispatcher(repository, deliveryResolver, backlogTaskService, messageSource, meterRegistry, clock)
    }

    private val userId: UUID = UUID.randomUUID()
    private val taskId: UUID = UUID.randomUUID()
    private val notificationId: UUID = UUID.randomUUID()

    private fun event(slotStartIso: String = "2026-05-13T10:00:00Z") = SlotReminderDueEvent(
        notificationId = notificationId,
        userId = userId,
        sessionId = UUID.randomUUID(),
        backlogTaskId = taskId,
        slotStartIso = slotStartIso,
        slotEndIso = "2026-05-13T11:00:00Z",
    )

    private fun pendingRow() = ScheduledNotificationEntity().apply {
        id = notificationId
        this.userId = this@SlotReminderDispatcherTest.userId
        sessionId = UUID.randomUUID()
        backlogTaskId = taskId
        slotStartIso = "2026-05-13T10:00:00Z"
        slotEndIso = "2026-05-13T11:00:00Z"
        fireAt = Instant.parse("2026-05-13T09:45:00Z")
        status = NotificationStatus.PENDING
        createdAt = Instant.parse("2026-05-13T08:00:00Z")
    }

    private fun context(channel: ConversationChannel) =
        ReminderContext(channel, Locale.ENGLISH, ZoneId.of("UTC"))

    private fun task(title: String): BacklogTask {
        val boardId = UUID.randomUUID()
        return BacklogTask(
            id = taskId,
            boardId = boardId,
            assigneeUserId = null,
            title = title,
            description = null,
            url = null,
            priority = null,
            deadline = null,
            estimatedMinutes = null,
            status = TaskStatus.TODO,
            category = BacklogTaskCategory(UUID.randomUUID(), boardId, "General", CategoryColor.SKY),
            tags = emptySet(),
            sortKey = "a",
            createdAt = Instant.parse("2026-05-13T08:00:00Z"),
            updatedAt = null,
            rescheduleCount = 0,
            lastScheduledInSessionId = null,
            relevantFrom = null,
        )
    }

    private fun counter(outcome: String) = meterRegistry
        .counter("tasker.notification.sent", "type", "slot_reminder", "channel", "telegram", "outcome", outcome)
        .count()

    @Test
    fun `eligible reminder renders localized text, sends it, and marks SENT`() {
        val row = pendingRow()
        val channel = mock<ConversationChannel>()
        whenever(channel.formatter).thenReturn(HtmlMessageFormatter)
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))
        whenever(deliveryResolver.resolve(userId)).thenReturn(context(channel))
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(task("Buy milk"))
        whenever(messageSource.getMessage(eq("notification.slot_reminder"), any(), any<Locale>()))
            .thenReturn("Reminder: Buy milk")

        dispatcher.on(event())

        val sent = argumentCaptor<ChannelMessage>()
        verify(channel).send(sent.capture())
        assertEquals("Reminder: Buy milk", (sent.firstValue as ChannelMessage.Text).text)
        assertEquals(NotificationStatus.SENT, row.status)
        assertEquals(clock.instant(), row.sentAt)
        assertEquals(1.0, counter("success"))
    }

    @Test
    fun `not eligible is skipped without sending`() {
        val row = pendingRow()
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))
        whenever(deliveryResolver.resolve(userId)).thenReturn(null)

        dispatcher.on(event())

        assertEquals(NotificationStatus.SKIPPED, row.status)
        assertEquals(1.0, counter("skipped"))
        verify(backlogTaskService, never()).findTask(any(), any())
    }

    @Test
    fun `missing task is skipped without sending`() {
        val row = pendingRow()
        val channel = mock<ConversationChannel>()
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))
        whenever(deliveryResolver.resolve(userId)).thenReturn(context(channel))
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(null)

        dispatcher.on(event())

        assertEquals(NotificationStatus.SKIPPED, row.status)
        verify(channel, never()).send(any())
    }

    @Test
    fun `reminder whose slot start already passed is expired without sending`() {
        val row = pendingRow().apply { slotStartIso = "2026-05-13T09:30:00Z" }
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))

        dispatcher.on(event(slotStartIso = "2026-05-13T09:30:00Z"))

        assertEquals(NotificationStatus.EXPIRED, row.status)
        verify(deliveryResolver, never()).resolve(any())
    }

    @Test
    fun `already-terminal row is ignored`() {
        val row = pendingRow().apply { status = NotificationStatus.SENT }
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))

        dispatcher.on(event())

        verify(deliveryResolver, never()).resolve(any())
    }

    @Test
    fun `send failures retry and then mark FAILED at the cap`() {
        val row = pendingRow()
        val channel = mock<ConversationChannel>()
        whenever(channel.formatter).thenReturn(HtmlMessageFormatter)
        whenever(channel.send(any())).thenThrow(RuntimeException("telegram down"))
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))
        whenever(deliveryResolver.resolve(userId)).thenReturn(context(channel))
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(task("Buy milk"))
        whenever(messageSource.getMessage(eq("notification.slot_reminder"), any(), any<Locale>()))
            .thenReturn("Reminder: Buy milk")

        dispatcher.on(event())
        assertEquals(1, row.attempts)
        assertEquals(NotificationStatus.PENDING, row.status)

        dispatcher.on(event())
        assertEquals(2, row.attempts)
        assertEquals(NotificationStatus.PENDING, row.status)

        dispatcher.on(event())
        assertEquals(3, row.attempts)
        assertEquals(NotificationStatus.FAILED, row.status)
        assertEquals(3.0, counter("failure"))
    }
}
