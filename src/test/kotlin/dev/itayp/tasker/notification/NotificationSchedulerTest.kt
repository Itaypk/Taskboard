package dev.itayp.tasker.notification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class NotificationSchedulerTest {

    @Mock lateinit var repository: ScheduledNotificationRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private val clock = Clock.fixed(Instant.parse("2026-05-13T09:50:00Z"), ZoneOffset.UTC)
    private val scheduler by lazy { NotificationScheduler(repository, eventPublisher, clock) }

    private fun due(slotStartIso: String) = ScheduledNotificationEntity().apply {
        id = UUID.randomUUID()
        userId = UUID.randomUUID()
        sessionId = UUID.randomUUID()
        backlogTaskId = UUID.randomUUID()
        this.slotStartIso = slotStartIso
        slotEndIso = "2026-05-13T11:00:00Z"
        fireAt = Instant.parse("2026-05-13T09:45:00Z")
        status = NotificationStatus.PENDING
        createdAt = Instant.parse("2026-05-13T08:00:00Z")
    }

    @Test
    fun `publishes an event for each due row and leaves the row untouched`() {
        val n = due(slotStartIso = "2026-05-13T10:00:00Z")
        whenever(
            repository.findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(any(), any(), any<Pageable>()),
        ) doReturn listOf(n)

        scheduler.poll()

        val event = argumentCaptor<Any>()
        verify(eventPublisher).publishEvent(event.capture())
        val due = event.firstValue as SlotReminderDueEvent
        assertEquals(n.id, due.notificationId)
        assertEquals(n.backlogTaskId, due.backlogTaskId)
        // The scheduler no longer owns status; the dispatcher records the terminal outcome.
        assertEquals(NotificationStatus.PENDING, n.status)
        verify(repository, never()).save(any())
    }

    @Test
    fun `no due rows publishes nothing`() {
        whenever(
            repository.findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(any(), any(), any<Pageable>()),
        ) doReturn emptyList()

        scheduler.poll()

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `a failure on one row does not stop the rest of the batch`() {
        val bad = due(slotStartIso = "2026-05-13T10:00:00Z")
        val good = due(slotStartIso = "2026-05-13T10:30:00Z")
        whenever(
            repository.findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(any(), any(), any<Pageable>()),
        ) doReturn listOf(bad, good)
        // First publish throws; the loop should still process the second row.
        doThrow(RuntimeException("boom")).doNothing().whenever(eventPublisher).publishEvent(any<Any>())

        scheduler.poll()

        verify(eventPublisher, times(2)).publishEvent(any<Any>())
    }
}
