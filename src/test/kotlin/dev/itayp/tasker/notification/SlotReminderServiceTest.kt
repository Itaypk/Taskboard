package dev.itayp.tasker.notification

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class SlotReminderServiceTest {

    @Mock lateinit var repository: ScheduledNotificationRepository

    // Fixed "now" = 2026-05-13T08:00:00Z. Slots well after this fire in the future.
    private val clock = Clock.fixed(Instant.parse("2026-05-13T08:00:00Z"), ZoneOffset.UTC)
    private val service by lazy { SlotReminderService(repository, clock) }

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()

    private fun task(vararg slots: AgreedTimeSlot) =
        AgreedPlanTask(taskId = taskId, title = "T", slots = slots.toList())

    @Test
    fun `added slot queues a reminder 15 minutes before start`() {
        val current = listOf(task(AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")))

        service.sync(userId, sessionId, previous = emptyList(), current = current)

        val captor = argumentCaptor<ScheduledNotificationEntity>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals(userId, saved.userId)
        assertEquals(sessionId, saved.sessionId)
        assertEquals(taskId, saved.backlogTaskId)
        assertEquals(NotificationStatus.PENDING, saved.status)
        assertEquals(NotificationType.SLOT_REMINDER, saved.type)
        assertEquals(Instant.parse("2026-05-13T09:45:00Z"), saved.fireAt)
    }

    @Test
    fun `removed slot cancels its pending reminder`() {
        val previous = listOf(task(AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")))

        service.sync(userId, sessionId, previous = previous, current = emptyList())

        verify(repository).cancelPending(eq(sessionId), eq(taskId), eq("2026-05-13T10:00:00Z"))
        verify(repository, never()).save(any())
    }

    @Test
    fun `unchanged slot is left alone`() {
        val slot = AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")

        service.sync(userId, sessionId, previous = listOf(task(slot)), current = listOf(task(slot)))

        verify(repository, never()).save(any())
        verify(repository, never()).cancelPending(any(), any(), any())
    }

    @Test
    fun `same-start end-time change does not move the reminder`() {
        val prevSlot = AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T11:00:00Z")
        val newSlot = AgreedTimeSlot(startIso = "2026-05-13T10:00:00Z", endIso = "2026-05-13T12:00:00Z")

        service.sync(userId, sessionId, previous = listOf(task(prevSlot)), current = listOf(task(newSlot)))

        // Same (taskId, startIso) key → neither added nor removed → no reminder churn.
        verify(repository, never()).save(any())
        verify(repository, never()).cancelPending(any(), any(), any())
    }

    @Test
    fun `slot whose reminder time is already in the past is not queued`() {
        // start 08:05 → fireAt 07:50, before now (08:00).
        val current = listOf(task(AgreedTimeSlot(startIso = "2026-05-13T08:05:00Z", endIso = "2026-05-13T09:00:00Z")))

        service.sync(userId, sessionId, previous = emptyList(), current = current)

        verify(repository, never()).save(any())
    }
}
