package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.noopUserCryptoService
import dev.itayp.tasker.model.TaskStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class BacklogTaskChangeServiceTest {

    @Mock lateinit var eventRepository: BacklogTaskChangeEventRepository
    @Mock lateinit var watermarkRepository: BacklogTaskWatermarkRepository

    private val now = Instant.parse("2026-05-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val crypto = noopUserCryptoService()
    private val service by lazy {
        BacklogTaskChangeService(eventRepository, watermarkRepository, crypto, clock)
    }

    private val userId = UUID.randomUUID()

    @Test
    fun `recordCreated saves a CREATED event with new status`() {
        whenever(eventRepository.save(any<BacklogTaskChangeEventEntity>())).thenAnswer { it.arguments[0] }
        val taskId = UUID.randomUUID()

        service.recordCreated(userId, taskId, "Buy bread", TaskStatus.TODO)

        val captor = argumentCaptor<BacklogTaskChangeEventEntity>()
        verify(eventRepository).save(captor.capture())
        assertEquals(BacklogTaskChangeType.CREATED, captor.firstValue.changeType)
        assertEquals(TaskStatus.TODO, captor.firstValue.newStatus)
        assertNull(captor.firstValue.previousStatus)
        assertEquals("Buy bread", captor.firstValue.taskTitleSnapshot?.toString(Charsets.UTF_8))
        assertEquals(now, captor.firstValue.occurredAt)
        assertEquals(taskId, captor.firstValue.taskId)
    }

    @Test
    fun `recordStatusChange returns null when status unchanged`() {
        val result = service.recordStatusChange(userId, UUID.randomUUID(), "t", TaskStatus.TODO, TaskStatus.TODO)

        assertNull(result)
        verify(eventRepository, never()).save(any<BacklogTaskChangeEventEntity>())
    }

    @Test
    fun `recordStatusChange persists transition`() {
        whenever(eventRepository.save(any<BacklogTaskChangeEventEntity>())).thenAnswer { it.arguments[0] }
        val taskId = UUID.randomUUID()

        service.recordStatusChange(userId, taskId, "Task", TaskStatus.TODO, TaskStatus.DONE)

        val captor = argumentCaptor<BacklogTaskChangeEventEntity>()
        verify(eventRepository).save(captor.capture())
        assertEquals(BacklogTaskChangeType.STATUS_CHANGED, captor.firstValue.changeType)
        assertEquals(TaskStatus.TODO, captor.firstValue.previousStatus)
        assertEquals(TaskStatus.DONE, captor.firstValue.newStatus)
    }

    @Test
    fun `recordDeleted captures last status snapshot`() {
        whenever(eventRepository.save(any<BacklogTaskChangeEventEntity>())).thenAnswer { it.arguments[0] }

        service.recordDeleted(userId, UUID.randomUUID(), "gone", TaskStatus.DONE)

        val captor = argumentCaptor<BacklogTaskChangeEventEntity>()
        verify(eventRepository).save(captor.capture())
        assertEquals(BacklogTaskChangeType.DELETED, captor.firstValue.changeType)
        assertEquals(TaskStatus.DONE, captor.firstValue.previousStatus)
        assertNull(captor.firstValue.newStatus)
    }

    @Test
    fun `bumpWatermark upserts the user watermark to now`() {
        whenever(watermarkRepository.save(any<BacklogTaskWatermarkEntity>())).thenAnswer { it.arguments[0] }

        service.bumpWatermark(userId)

        val captor = argumentCaptor<BacklogTaskWatermarkEntity>()
        verify(watermarkRepository).save(captor.capture())
        assertEquals(userId, captor.firstValue.userId)
        assertEquals(now, captor.firstValue.tasksChangedAt)
    }

    @Test
    fun `changedSince delegates to the watermark existence query`() {
        val since = now.minusSeconds(60)
        whenever(watermarkRepository.existsByUserIdAndTasksChangedAtGreaterThanEqual(userId, since))
            .thenReturn(true)

        assertTrue(service.changedSince(userId, since))
        verify(watermarkRepository).existsByUserIdAndTasksChangedAtGreaterThanEqual(userId, since)
    }

    @Test
    fun `summarizeSince classifies completed-from-backlog vs added-during-window`() {
        val backlogTask = UUID.randomUUID()  // existed before window
        val newTask = UUID.randomUUID()      // created in window
        val since = now.minusSeconds(3600)

        val events = listOf(
            event(BacklogTaskChangeType.CREATED, newTask, "New task", newStatus = TaskStatus.TODO),
            event(BacklogTaskChangeType.STATUS_CHANGED, newTask, "New task",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.STATUS_CHANGED, backlogTask, "Old task",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
        )
        whenever(eventRepository.findAllByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(userId, since))
            .thenReturn(events)

        val summary = service.summarizeSince(userId, since)

        assertEquals(2, summary.completed.size)
        assertEquals(1, summary.completedFromBacklog.size)
        assertEquals(backlogTask, summary.completedFromBacklog.single().taskId)
        assertEquals(1, summary.completedAddedDuringWindow.size)
        assertEquals(newTask, summary.completedAddedDuringWindow.single().taskId)
        assertEquals(1, summary.createdDuringWindow.size)
        assertEquals(3, summary.totalEvents)
    }

    @Test
    fun `summarizeSince treats DONE-then-TODO as reopened, not completed`() {
        val taskId = UUID.randomUUID()
        val since = now.minusSeconds(3600)
        val events = listOf(
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.DONE, newStatus = TaskStatus.TODO),
        )
        whenever(eventRepository.findAllByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(userId, since))
            .thenReturn(events)

        val summary = service.summarizeSince(userId, since)

        assertTrue(summary.completed.isEmpty())
        assertEquals(1, summary.reopened.size)
    }

    @Test
    fun `summarizeSince drops earlier transitions when task is later deleted`() {
        val taskId = UUID.randomUUID()
        val since = now.minusSeconds(3600)
        val events = listOf(
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.DELETED, taskId, "x", prev = TaskStatus.DONE),
        )
        whenever(eventRepository.findAllByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(userId, since))
            .thenReturn(events)

        val summary = service.summarizeSince(userId, since)

        assertTrue(summary.completed.isEmpty())
        assertEquals(1, summary.deleted.size)
    }

    private fun event(
        type: BacklogTaskChangeType,
        taskId: UUID,
        title: String,
        prev: TaskStatus? = null,
        newStatus: TaskStatus? = null,
    ) = BacklogTaskChangeEventEntity().apply {
        this.id = UUID.randomUUID()
        this.userId = this@BacklogTaskChangeServiceTest.userId
        this.taskId = taskId
        this.changeType = type
        this.previousStatus = prev
        this.newStatus = newStatus
        this.taskTitleSnapshot = title.toByteArray(Charsets.UTF_8)
        this.occurredAt = now
    }
}
