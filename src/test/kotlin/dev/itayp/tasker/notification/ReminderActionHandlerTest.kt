package dev.itayp.tasker.notification

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.MessageSource
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ReminderActionHandlerTest {

    @Mock lateinit var repository: ScheduledNotificationRepository
    @Mock lateinit var slotReminderService: SlotReminderService
    @Mock lateinit var backlogTaskService: BacklogTaskService
    @Mock lateinit var userSettingsService: UserSettingsService
    @Mock lateinit var messageSource: MessageSource

    private val meterRegistry = SimpleMeterRegistry()
    private val handler by lazy {
        ReminderActionHandler(repository, slotReminderService, backlogTaskService, userSettingsService, messageSource, meterRegistry)
    }

    private val userId: UUID = UUID.randomUUID()
    private val taskId: UUID = UUID.randomUUID()
    private val notificationId: UUID = UUID.randomUUID()

    private fun row() = ScheduledNotificationEntity().apply {
        id = notificationId
        this.userId = this@ReminderActionHandlerTest.userId
        sessionId = UUID.randomUUID()
        backlogTaskId = taskId
        slotStartIso = "2026-05-13T10:00:00Z"
        slotEndIso = "2026-05-13T11:00:00Z"
        fireAt = Instant.parse("2026-05-13T09:45:00Z")
        status = NotificationStatus.SENT
        createdAt = Instant.parse("2026-05-13T08:00:00Z")
    }

    private fun channel(): ConversationChannel = mock()

    private fun task(): BacklogTask {
        val boardId = UUID.randomUUID()
        return BacklogTask(
            id = taskId, boardId = boardId, assigneeUserId = null, title = "Buy milk", description = null,
            url = null, priority = null, deadline = null, estimatedMinutes = null, status = TaskStatus.DONE,
            category = BacklogTaskCategory(UUID.randomUUID(), boardId, "General", CategoryColor.SKY),
            tags = emptySet(), sortKey = "a", createdAt = Instant.parse("2026-05-13T08:00:00Z"),
            updatedAt = null, rescheduleCount = 0, lastScheduledInSessionId = null, relevantFrom = null,
        )
    }

    private fun stubLocaleAndMessages() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        // anyOrNull() for the args array: the confirmation messages pass null args (no-arg keys),
        // and mockito-kotlin's any() is reified and would not match null.
        whenever(messageSource.getMessage(any<String>(), anyOrNull(), any<Locale>())).thenReturn("ok")
    }

    @Test
    fun `non-reminder callback data is not handled`() {
        assertFalse(handler.processReminderResponse(userId, channel(), "plan_keep"))
        verify(repository, never()).findById(any())
    }

    @Test
    fun `ack confirms without changing state`() {
        stubLocaleAndMessages()
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row()))
        val channel = channel()

        val handled = handler.processReminderResponse(userId, channel, ReminderAction.ACK.callbackData(notificationId))

        assertTrue(handled)
        verify(channel).send(any<ChannelMessage.Text>())
        verify(slotReminderService, never()).snooze(any(), any())
        verify(backlogTaskService, never()).markDone(any(), any())
    }

    @Test
    fun `snooze hour re-queues an hour out`() {
        stubLocaleAndMessages()
        val row = row()
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))

        handler.processReminderResponse(userId, channel(), ReminderAction.SNOOZE_HOUR.callbackData(notificationId))

        verify(slotReminderService).snooze(eq(row), eq(Duration.ofHours(1)))
    }

    @Test
    fun `snooze day re-queues a day out`() {
        stubLocaleAndMessages()
        val row = row()
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row))

        handler.processReminderResponse(userId, channel(), ReminderAction.SNOOZE_DAY.callbackData(notificationId))

        verify(slotReminderService).snooze(eq(row), eq(Duration.ofDays(1)))
    }

    @Test
    fun `mark done completes the task`() {
        stubLocaleAndMessages()
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row()))
        whenever(backlogTaskService.markDone(userId, taskId)).thenReturn(task())
        val channel = channel()
        whenever(channel.formatter).thenReturn(HtmlMessageFormatter)

        handler.processReminderResponse(userId, channel, ReminderAction.MARK_DONE.callbackData(notificationId))

        verify(backlogTaskService).markDone(userId, taskId)
        verify(channel).send(any<ChannelMessage.Text>())
    }

    @Test
    fun `mark done on a vanished task reports gracefully`() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(messageSource.getMessage(eq("notification.done.task_gone"), eq(null), any<Locale>())).thenReturn("gone")
        whenever(repository.findById(notificationId)).thenReturn(Optional.of(row()))
        whenever(backlogTaskService.markDone(userId, taskId)).thenReturn(null)
        val channel = channel()

        handler.processReminderResponse(userId, channel, ReminderAction.MARK_DONE.callbackData(notificationId))

        val sent = argumentCaptor<ChannelMessage>()
        verify(channel).send(sent.capture())
        assertEquals("gone", (sent.firstValue as ChannelMessage.Text).text)
    }

    @Test
    fun `a stale or foreign row reports the reminder is no longer available`() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(messageSource.getMessage(eq("notification.action.expired"), eq(null), any<Locale>())).thenReturn("expired")
        whenever(repository.findById(notificationId)).thenReturn(Optional.empty())
        val channel = channel()

        val handled = handler.processReminderResponse(userId, channel, ReminderAction.ACK.callbackData(notificationId))

        assertTrue(handled)
        val sent = argumentCaptor<ChannelMessage>()
        verify(channel).send(sent.capture())
        assertEquals("expired", (sent.firstValue as ChannelMessage.Text).text)
    }
}
