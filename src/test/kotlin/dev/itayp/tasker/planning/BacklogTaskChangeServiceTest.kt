package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.newTestBoardCryptoService
import dev.itayp.tasker.model.TaskStatus
import org.junit.jupiter.api.BeforeEach
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
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class BacklogTaskChangeServiceTest {

    @Mock lateinit var eventRepository: BacklogTaskChangeEventRepository
    @Mock lateinit var watermarkRepository: BacklogTaskWatermarkRepository

    private val now = Instant.parse("2026-05-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    // Real board crypto so snapshots are genuinely board-keyed (AAD-bound to the board id).
    private val boardCrypto = newTestBoardCryptoService()
    private val service by lazy {
        BacklogTaskChangeService(eventRepository, watermarkRepository, boardCrypto, clock)
    }

    private val boardId = UUID.randomUUID()
    private val actorUserId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        boardCrypto.ensureBoardKey(boardId)
    }

    @Test
    fun `recordCreated saves a CREATED event keyed by board with the actor and board-encrypted title`() {
        whenever(eventRepository.save(any<BacklogTaskChangeEventEntity>())).thenAnswer { it.arguments[0] }
        val taskId = UUID.randomUUID()

        service.recordCreated(boardId, actorUserId, taskId, "Buy bread", TaskStatus.TODO)

        val captor = argumentCaptor<BacklogTaskChangeEventEntity>()
        verify(eventRepository).save(captor.capture())
        assertEquals(BacklogTaskChangeType.CREATED, captor.firstValue.changeType)
        assertEquals(TaskStatus.TODO, captor.firstValue.newStatus)
        assertNull(captor.firstValue.previousStatus)
        assertEquals(boardId, captor.firstValue.boardId)
        assertEquals(actorUserId, captor.firstValue.actorUserId)
        assertEquals(taskId, captor.firstValue.taskId)
        assertEquals(now, captor.firstValue.occurredAt)
        // Snapshot is ciphertext under the board DEK, recoverable only with the board id.
        assertEquals("Buy bread", boardCrypto.decrypt(boardId, captor.firstValue.taskTitleSnapshot))
    }

    @Test
    fun `recordStatusChange returns null when status unchanged`() {
        val result = service.recordStatusChange(
            boardId, actorUserId, UUID.randomUUID(), "t", TaskStatus.TODO, TaskStatus.TODO,
        )

        assertNull(result)
        verify(eventRepository, never()).save(any<BacklogTaskChangeEventEntity>())
    }

    @Test
    fun `recordStatusChange persists transition`() {
        whenever(eventRepository.save(any<BacklogTaskChangeEventEntity>())).thenAnswer { it.arguments[0] }
        val taskId = UUID.randomUUID()

        service.recordStatusChange(boardId, actorUserId, taskId, "Task", TaskStatus.TODO, TaskStatus.DONE)

        val captor = argumentCaptor<BacklogTaskChangeEventEntity>()
        verify(eventRepository).save(captor.capture())
        assertEquals(BacklogTaskChangeType.STATUS_CHANGED, captor.firstValue.changeType)
        assertEquals(TaskStatus.TODO, captor.firstValue.previousStatus)
        assertEquals(TaskStatus.DONE, captor.firstValue.newStatus)
    }

    @Test
    fun `recordDeleted captures last status snapshot`() {
        whenever(eventRepository.save(any<BacklogTaskChangeEventEntity>())).thenAnswer { it.arguments[0] }

        service.recordDeleted(boardId, actorUserId, UUID.randomUUID(), "gone", TaskStatus.DONE)

        val captor = argumentCaptor<BacklogTaskChangeEventEntity>()
        verify(eventRepository).save(captor.capture())
        assertEquals(BacklogTaskChangeType.DELETED, captor.firstValue.changeType)
        assertEquals(TaskStatus.DONE, captor.firstValue.previousStatus)
        assertNull(captor.firstValue.newStatus)
    }

    @Test
    fun `bumpWatermark upserts the board watermark to now`() {
        whenever(watermarkRepository.findById(boardId)).thenReturn(Optional.empty())
        whenever(watermarkRepository.save(any<BacklogTaskWatermarkEntity>())).thenAnswer { it.arguments[0] }

        service.bumpWatermark(boardId)

        val captor = argumentCaptor<BacklogTaskWatermarkEntity>()
        verify(watermarkRepository).save(captor.capture())
        assertEquals(boardId, captor.firstValue.boardId)
        assertEquals(now, captor.firstValue.tasksChangedAt)
    }

    @Test
    fun `bumpTags on a fresh board seeds tasks and records the tag change`() {
        whenever(watermarkRepository.findById(boardId)).thenReturn(Optional.empty())
        whenever(watermarkRepository.save(any<BacklogTaskWatermarkEntity>())).thenAnswer { it.arguments[0] }

        service.bumpTags(boardId)

        val captor = argumentCaptor<BacklogTaskWatermarkEntity>()
        verify(watermarkRepository).save(captor.capture())
        assertEquals(now, captor.firstValue.tagsChangedAt)
        // The non-null tasks column is seeded so the row is valid even on a tags-first bump.
        assertEquals(now, captor.firstValue.tasksChangedAt)
    }

    @Test
    fun `bumpCategories preserves the existing tasks watermark`() {
        val earlier = now.minusSeconds(3600)
        val existing = BacklogTaskWatermarkEntity().apply {
            this.boardId = this@BacklogTaskChangeServiceTest.boardId
            this.tasksChangedAt = earlier
        }
        whenever(watermarkRepository.findById(boardId)).thenReturn(Optional.of(existing))
        whenever(watermarkRepository.save(any<BacklogTaskWatermarkEntity>())).thenAnswer { it.arguments[0] }

        service.bumpCategories(boardId)

        val captor = argumentCaptor<BacklogTaskWatermarkEntity>()
        verify(watermarkRepository).save(captor.capture())
        assertEquals(now, captor.firstValue.categoriesChangedAt)
        assertEquals(earlier, captor.firstValue.tasksChangedAt)
    }

    @Test
    fun `readWatermark returns the board row`() {
        val entity = BacklogTaskWatermarkEntity().apply {
            this.boardId = this@BacklogTaskChangeServiceTest.boardId
            this.tasksChangedAt = now
        }
        whenever(watermarkRepository.findById(boardId)).thenReturn(Optional.of(entity))

        assertEquals(entity, service.readWatermark(boardId))
    }

    @Test
    fun `summarizeSince with no boards is empty without querying`() {
        val summary = service.summarizeSince(emptyList(), now.minusSeconds(3600))

        assertEquals(0, summary.totalEvents)
        verify(eventRepository, never())
            .findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(any(), any())
    }

    @Test
    fun `summarizeSince classifies completed-from-backlog vs added-during-window`() {
        val backlogTask = UUID.randomUUID()  // existed before window
        val newTask = UUID.randomUUID()      // created in window
        val since = now.minusSeconds(3600)
        val boardIds = listOf(boardId)

        val events = listOf(
            event(BacklogTaskChangeType.CREATED, newTask, "New task", newStatus = TaskStatus.TODO),
            event(BacklogTaskChangeType.STATUS_CHANGED, newTask, "New task",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.STATUS_CHANGED, backlogTask, "Old task",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
        )
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(events)

        val summary = service.summarizeSince(boardIds, since)

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
        val boardIds = listOf(boardId)
        val events = listOf(
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.DONE, newStatus = TaskStatus.TODO),
        )
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(events)

        val summary = service.summarizeSince(boardIds, since)

        assertTrue(summary.completed.isEmpty())
        assertEquals(1, summary.reopened.size)
    }

    @Test
    fun `summarizeSince drops earlier transitions when task is later deleted`() {
        val taskId = UUID.randomUUID()
        val since = now.minusSeconds(3600)
        val boardIds = listOf(boardId)
        val events = listOf(
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.DELETED, taskId, "x", prev = TaskStatus.DONE),
        )
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(events)

        val summary = service.summarizeSince(boardIds, since)

        assertTrue(summary.completed.isEmpty())
        assertEquals(1, summary.deleted.size)
    }

    @Test
    fun `summarizeSince reports an archived open task as removed and drops it from newly added`() {
        val taskId = UUID.randomUUID()
        val since = now.minusSeconds(3600)
        val boardIds = listOf(boardId)
        val events = listOf(
            event(BacklogTaskChangeType.CREATED, taskId, "x", newStatus = TaskStatus.TODO),
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.ARCHIVED),
        )
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(events)

        val summary = service.summarizeSince(boardIds, since)

        assertTrue(summary.createdDuringWindow.isEmpty())
        assertEquals(1, summary.removed.size)
        assertEquals(taskId, summary.removed.single().taskId)
    }

    @Test
    fun `summarizeSince keeps DONE-then-ARCHIVED as completed, not removed`() {
        val taskId = UUID.randomUUID()
        val since = now.minusSeconds(3600)
        val boardIds = listOf(boardId)
        val events = listOf(
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.DONE),
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.DONE, newStatus = TaskStatus.ARCHIVED),
        )
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(events)

        val summary = service.summarizeSince(boardIds, since)

        assertEquals(1, summary.completed.size)
        assertTrue(summary.removed.isEmpty())
    }

    @Test
    fun `summarizeSince treats un-archiving as reopened`() {
        val taskId = UUID.randomUUID()
        val since = now.minusSeconds(3600)
        val boardIds = listOf(boardId)
        val events = listOf(
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.TODO, newStatus = TaskStatus.ARCHIVED),
            event(BacklogTaskChangeType.STATUS_CHANGED, taskId, "x",
                  prev = TaskStatus.ARCHIVED, newStatus = TaskStatus.TODO),
        )
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(events)

        val summary = service.summarizeSince(boardIds, since)

        assertTrue(summary.removed.isEmpty())
        assertEquals(1, summary.reopened.size)
    }

    @Test
    fun `summarizeSince cannot decrypt a snapshot under another board's key`() {
        // Snapshot sealed under boardId, but the event claims to belong to a different board:
        // the board DEK / AAD won't match, so decryption fails — a feed entry is bound to its board.
        val otherBoard = UUID.randomUUID()
        boardCrypto.ensureBoardKey(otherBoard)
        val since = now.minusSeconds(3600)
        val boardIds = listOf(otherBoard)
        val mismatched = BacklogTaskChangeEventEntity().apply {
            this.id = UUID.randomUUID()
            this.boardId = otherBoard
            this.actorUserId = actorUserId
            this.taskId = UUID.randomUUID()
            this.changeType = BacklogTaskChangeType.CREATED
            this.newStatus = TaskStatus.TODO
            // sealed under the wrong board (the test's boardId, not otherBoard)
            this.taskTitleSnapshot = boardCrypto.encrypt(this@BacklogTaskChangeServiceTest.boardId, "secret")
            this.occurredAt = now
        }
        whenever(eventRepository.findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(boardIds, since))
            .thenReturn(listOf(mismatched))

        assertFails { service.summarizeSince(boardIds, since) }
    }

    private fun event(
        type: BacklogTaskChangeType,
        taskId: UUID,
        title: String,
        prev: TaskStatus? = null,
        newStatus: TaskStatus? = null,
    ) = BacklogTaskChangeEventEntity().apply {
        this.id = UUID.randomUUID()
        this.boardId = this@BacklogTaskChangeServiceTest.boardId
        this.actorUserId = this@BacklogTaskChangeServiceTest.actorUserId
        this.taskId = taskId
        this.changeType = type
        this.previousStatus = prev
        this.newStatus = newStatus
        this.taskTitleSnapshot = boardCrypto.encrypt(this@BacklogTaskChangeServiceTest.boardId, title)
        this.occurredAt = now
    }
}
