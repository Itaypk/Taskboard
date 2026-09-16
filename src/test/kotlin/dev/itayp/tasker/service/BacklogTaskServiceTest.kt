package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.RecurrenceKind
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.RecurrenceInput
import dev.itayp.tasker.model.request.ReorderTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.planning.PlanWatermarkService
import dev.itayp.tasker.planning.TaskCompletionCancellationService
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class BacklogTaskServiceTest {

    @Mock private lateinit var backlogTaskRepository: BacklogTaskRepository
    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var tagRepository: BacklogTaskTagRepository
    @Mock private lateinit var taskChangeService: BacklogTaskChangeService
    @Mock private lateinit var planWatermarkService: PlanWatermarkService
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var userRepository: UserRepository
    @Mock private lateinit var clock: Clock
    @Mock private lateinit var taskCompletionCancellationService: TaskCompletionCancellationService
    @Mock private lateinit var plannedTaskService: dev.itayp.tasker.planning.PlannedTaskService

    private val boardCrypto = noopBoardCryptoService()

    private val service: BacklogTaskService by lazy {
        BacklogTaskService(
            backlogTaskRepository,
            categoryRepository,
            tagRepository,
            taskChangeService,
            planWatermarkService,
            userSettingsService,
            boardMembershipService,
            boardCrypto,
            userRepository,
            clock,
            taskCompletionCancellationService,
            plannedTaskService,
        )
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    // --- getTasks / planner bridge ---

    @Test
    fun `getTasks returns tasks mapped to domain`() {
        val category = categoryEntity()
        val entity = taskEntity(category, title = "My Task")
        whenever(backlogTaskRepository.findAllByBoardIdAndStatusNotOrderBySortKeyAsc(boardId, TaskStatus.ARCHIVED))
            .thenReturn(listOf(entity))

        val result = service.getTasks(userId, boardId, null)

        assertEquals(1, result.size)
        assertEquals("My Task", result[0].title)
        assertEquals(TaskStatus.TODO, result[0].status)
    }

    @Test
    fun `getTasksAcrossBoards unions tasks over every board the user belongs to`() {
        val otherBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId, otherBoardId))
        whenever(backlogTaskRepository.findAllByBoardIdInAndStatusNotOrderBySortKeyAsc(
            listOf(boardId, otherBoardId), TaskStatus.ARCHIVED,
        )).thenReturn(listOf(
            taskEntity(categoryEntity(), title = "On default"),
            taskEntity(categoryEntity(), title = "On other"),
        ))

        val result = service.getTasksAcrossBoards(userId, null)

        assertEquals(listOf("On default", "On other"), result.map { it.title })
    }

    @Test
    fun `getTasksAcrossBoards drops tasks hidden from the assistant`() {
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(backlogTaskRepository.findAllByBoardIdInAndStatusNotOrderBySortKeyAsc(
            listOf(boardId), TaskStatus.ARCHIVED,
        )).thenReturn(listOf(
            taskEntity(categoryEntity(), title = "Visible"),
            taskEntity(categoryEntity(), title = "Hidden", hiddenFromAssistant = true),
        ))

        val result = service.getTasksAcrossBoards(userId, null)

        assertEquals(listOf("Visible"), result.map { it.title })
    }

    @Test
    fun `findTask resolves a task on any of the user's boards`() {
        val taskId = UUID.randomUUID()
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(backlogTaskRepository.findByIdAndBoardIdIn(taskId, listOf(boardId)))
            .thenReturn(taskEntity(categoryEntity(), id = taskId, title = "Found"))

        val result = service.findTask(userId, taskId)

        assertEquals("Found", result?.title)
        assertEquals(boardId, result?.boardId)
    }

    // --- createTask ---

    @Test
    fun `createTask maps priority and status from lowercase strings`() {
        val catId = UUID.randomUUID()
        val cat = categoryEntity(id = catId)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(cat)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(
            title = "Task",
            priority = "high",
            status = "todo",
            categoryId = catId.toString(),
        ))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertEquals(TaskPriority.HIGH, captor.firstValue.priority)
        assertEquals(TaskStatus.TODO, captor.firstValue.status)
    }

    @Test
    fun `createTask assigns a sort key`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertNotNull(captor.firstValue.sortKey)
    }

    @Test
    fun `createTask assigns a sort key after the current max`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn("M")
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertTrue(captor.firstValue.sortKey!! > "M", "new sort key should be after 'M'")
    }

    @Test
    fun `createTask parses deadline string to LocalDate`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(
            title = "Task",
            deadline = "2026-05-01",
            categoryId = catId.toString(),
        ))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertEquals(2026, captor.firstValue.deadline?.year)
        assertEquals(5, captor.firstValue.deadline?.monthValue)
        assertEquals(1, captor.firstValue.deadline?.dayOfMonth)
    }

    @Test
    fun `createTask sets createdAt and leaves updatedAt null`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertNotNull(captor.firstValue.createdAt)
        assertNull(captor.firstValue.updatedAt)
    }

    @Test
    fun `createTask throws when category not found`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.createTask(userId, boardId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))
        }
    }

    @Test
    fun `createTask creates new tag when none matches`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        whenever(tagRepository.save(any<BacklogTaskTagEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskTagEntity).also { it.id = UUID.randomUUID() }
        }
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(
            title = "Task",
            categoryId = catId.toString(),
            tags = listOf(TagInput(label = "urgent", colorId = "coral")),
        ))

        val captor = argumentCaptor<BacklogTaskTagEntity>()
        verify(tagRepository).save(captor.capture())
        assertEquals("urgent", captor.firstValue.label)
        assertEquals(TagColor.CORAL, captor.firstValue.colorId)
        // A newly-born tag bumps the tags watermark so open tabs refetch the tag list.
        verify(taskChangeService).bumpTags(boardId)
    }

    @Test
    fun `createTask reuses existing tag when label and color match`() {
        val catId = UUID.randomUUID()
        val existingTag = tagEntity(label = "urgent", colorId = TagColor.CORAL)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(listOf(existingTag))
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, boardId, CreateBacklogTaskRequest(
            title = "Task",
            categoryId = catId.toString(),
            tags = listOf(TagInput(label = "urgent", colorId = "coral")),
        ))

        verify(tagRepository, never()).save(any())
        // No tag was created, so the tags watermark must not move.
        verify(taskChangeService, never()).bumpTags(any())
    }

    // --- updateTask ---

    @Test
    fun `updateTask updates task fields and sets updatedAt`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, title = "Old Title")
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "New Title",
            status = "done",
            categoryId = catId.toString(),
        ))

        assertEquals("New Title", existingEntity.title?.toString(Charsets.UTF_8))
        assertEquals(TaskStatus.DONE, existingEntity.status)
        assertNotNull(existingEntity.updatedAt)
    }

    @Test
    fun `updateTask cancels upcoming slots when task is completed and it has a scheduled session`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, lastScheduledInSessionId = sessionId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Title",
            status = "done",
            categoryId = catId.toString(),
        ))

        verify(taskCompletionCancellationService).cancelUpcomingSlots(userId, sessionId, taskId)
    }

    @Test
    fun `updateTask does not cancel slots when task is completed without a scheduled session`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Title",
            status = "done",
            categoryId = catId.toString(),
        ))

        verify(taskCompletionCancellationService, never()).cancelUpcomingSlots(any(), any(), any())
    }

    @Test
    fun `updateTask does not cancel slots when an already-done task is re-saved`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, lastScheduledInSessionId = sessionId)
        existingEntity.status = TaskStatus.DONE
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Title still done",
            status = "done",
            categoryId = catId.toString(),
        ))

        verify(taskCompletionCancellationService, never()).cancelUpcomingSlots(any(), any(), any())
    }

    @Test
    fun `updateTask cancels upcoming slots when task is archived and it has a scheduled session`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, lastScheduledInSessionId = sessionId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Title",
            status = "archived",
            categoryId = catId.toString(),
        ))

        verify(taskCompletionCancellationService).cancelUpcomingSlots(userId, sessionId, taskId)
    }

    @Test
    fun `updateTask does not cancel slots again when moving from done to archived`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, lastScheduledInSessionId = sessionId)
        existingEntity.status = TaskStatus.DONE
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Title",
            status = "archived",
            categoryId = catId.toString(),
        ))

        // Already cancelled when the task first went DONE — moving between terminal statuses shouldn't
        // dispatch a second round of cancellations.
        verify(taskCompletionCancellationService, never()).cancelUpcomingSlots(any(), any(), any())
    }

    // --- recurring tasks ---

    private val rentRule = RecurrenceInput(kind = "MONTHLY", day = 25, dueWithinDays = 7)

    private fun stubToday(isoInstant: String) {
        // userSettingsService is a bare mock, so the zone lookup falls back to UTC.
        whenever(clock.instant()).thenReturn(Instant.parse(isoInstant))
    }

    @Test
    fun `completing a recurring task saves a DONE copy and rolls the original forward`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val existing = taskEntity(categoryEntity(id = catId), id = taskId, title = "Rent", lastScheduledInSessionId = sessionId).apply {
            recurrenceKind = RecurrenceKind.MONTHLY
            recurrenceDay = 25
            dueWithinDays = 7
            relevantFrom = LocalDate.parse("2026-09-25")
            deadline = LocalDate.parse("2026-10-02")
            rescheduleCount = 2
        }
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(existing.category)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()
        stubToday("2026-09-27T10:00:00Z")

        val result = service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Rent",
            status = "done",
            categoryId = catId.toString(),
            relevantFrom = "2026-09-25",
            recurrence = rentRule,
        ))

        val saved = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository, org.mockito.kotlin.times(2)).save(saved.capture())
        val copy = saved.allValues.single { it.recurrenceSourceId == taskId }
        assertEquals(TaskStatus.DONE, copy.status)
        assertNull(copy.recurrenceKind)
        assertEquals(LocalDate.parse("2026-09-25"), copy.relevantFrom)
        assertEquals(LocalDate.parse("2026-10-02"), copy.deadline)
        assertEquals(LocalDate.parse("2026-09-27"), copy.lastCompletedOn)
        assertNull(copy.lastScheduledInSessionId)

        assertEquals(TaskStatus.TODO, result.status)
        assertEquals(LocalDate.parse("2026-10-25"), result.relevantFrom)
        assertEquals(LocalDate.parse("2026-11-01"), result.deadline)
        assertEquals(LocalDate.parse("2026-09-27"), result.lastCompletedOn)
        assertEquals(0, result.rescheduleCount)
        // Kept so the plan view still lists the task in the week it was planned in.
        assertEquals(sessionId, result.lastScheduledInSessionId)

        verify(taskChangeService).recordStatusChange(boardId, userId, copy.id!!, "Rent", TaskStatus.TODO, TaskStatus.DONE)
        verify(taskChangeService, never()).recordStatusChange(any(), any(), eq(taskId), any(), any(), eq(TaskStatus.DONE))
        verify(taskChangeService, never()).recordCreated(any(), any(), any(), any(), any())
        verify(taskCompletionCancellationService).cancelUpcomingSlots(userId, sessionId, taskId)
    }

    @Test
    fun `markDone rolls a recurring task forward through the rebuilt request`() {
        val taskId = UUID.randomUUID()
        val category = categoryEntity()
        val existing = taskEntity(category, id = taskId).apply {
            recurrenceKind = RecurrenceKind.EVERY_N_MONTHS
            recurrenceEvery = 6
            relevantFrom = LocalDate.parse("2026-09-01")
        }
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(backlogTaskRepository.findByIdAndBoardIdIn(taskId, listOf(boardId))).thenReturn(existing)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)
        whenever(categoryRepository.findByIdAndBoardId(category.id!!, boardId)).thenReturn(category)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()
        stubToday("2026-09-13T08:00:00Z")

        val result = service.markDone(userId, taskId)!!

        assertEquals(TaskStatus.TODO, result.status)
        assertEquals(RecurrenceKind.EVERY_N_MONTHS, result.recurrence?.kind)
        assertEquals(LocalDate.parse("2027-03-13"), result.relevantFrom)
    }

    @Test
    fun `markDone is a no-op for a recurring task already completed today`() {
        val taskId = UUID.randomUUID()
        val existing = taskEntity(categoryEntity(), id = taskId).apply {
            recurrenceKind = RecurrenceKind.EVERY_N_DAYS
            recurrenceEvery = 1
            lastCompletedOn = LocalDate.parse("2026-09-13")
            relevantFrom = LocalDate.parse("2026-09-14")
        }
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(backlogTaskRepository.findByIdAndBoardIdIn(taskId, listOf(boardId))).thenReturn(existing)
        stubToday("2026-09-13T20:00:00Z")

        val result = service.markDone(userId, taskId)

        assertEquals(LocalDate.parse("2026-09-14"), result?.relevantFrom)
        verify(backlogTaskRepository, never()).save(any<BacklogTaskEntity>())
    }

    @Test
    fun `a second completion on the same day rolls nothing and keeps the stored schedule`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        // State after a first completion: already rolled to October, stamped with today's date.
        val existing = taskEntity(categoryEntity(id = catId), id = taskId, title = "Rent", lastScheduledInSessionId = sessionId).apply {
            recurrenceKind = RecurrenceKind.MONTHLY
            recurrenceDay = 25
            dueWithinDays = 7
            relevantFrom = LocalDate.parse("2026-10-25")
            deadline = LocalDate.parse("2026-11-01")
            lastCompletedOn = LocalDate.parse("2026-09-27")
        }
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(existing.category)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()
        stubToday("2026-09-27T10:05:00Z")

        // The double tap replays the pre-roll payload — the tab that sent it never saw the new dates.
        val result = service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Rent",
            status = "done",
            categoryId = catId.toString(),
            relevantFrom = "2026-09-25",
            recurrence = rentRule,
        ))

        val saved = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(saved.capture())
        assertNull(saved.allValues.find { it.recurrenceSourceId == taskId }, "no second DONE copy")
        assertEquals(TaskStatus.TODO, result.status)
        // The stale relevantFrom must not land: it would drag the occurrence back into the past.
        assertEquals(LocalDate.parse("2026-10-25"), result.relevantFrom)
        assertEquals(LocalDate.parse("2026-11-01"), result.deadline)
        assertEquals(LocalDate.parse("2026-09-27"), result.lastCompletedOn)
        // Already cancelled by the first completion; a repeat must not re-send the invite cancellations.
        verify(taskCompletionCancellationService, never()).cancelUpcomingSlots(any(), any(), any())
    }

    // --- task links ---

    @Test
    fun `an unchanged in-app link is accepted, so a seeded tutorial card can be completed`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val existing = taskEntity(categoryEntity(id = catId), id = taskId, title = "Save your tasks").apply {
            url = "/settings/general"
        }
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(existing.category)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()

        val result = service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Save your tasks",
            url = "/settings/general",
            status = "done",
            categoryId = catId.toString(),
        ))

        assertEquals(TaskStatus.DONE, result.status)
        assertEquals("/settings/general", result.url)
    }

    @Test
    fun `an update cannot introduce a new in-app link`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val existing = taskEntity(categoryEntity(id = catId), id = taskId).apply { url = "/settings/general" }
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)

        assertFailsWith<InvalidTaskUrlException> {
            service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
                title = "Task",
                url = "app:clear-tutorial",
                categoryId = catId.toString(),
            ))
        }
        verify(backlogTaskRepository, never()).save(any<BacklogTaskEntity>())
    }

    @Test
    fun `create rejects a link that is not a web URL`() {
        // The external API hands the service its own DTO, so bean validation never runs for it.
        assertFailsWith<InvalidTaskUrlException> {
            service.createTask(userId, boardId, CreateBacklogTaskRequest(
                title = "Task",
                url = "app:clear-tutorial",
                categoryId = UUID.randomUUID().toString(),
            ))
        }
        verify(backlogTaskRepository, never()).save(any<BacklogTaskEntity>())
    }

    @Test
    fun `archive keeps the recurrence rule and does not roll forward`() {
        val taskId = UUID.randomUUID()
        val category = categoryEntity()
        val existing = taskEntity(category, id = taskId).apply {
            recurrenceKind = RecurrenceKind.WEEKLY
            recurrenceDay = 2
            relevantFrom = LocalDate.parse("2026-09-15")
        }
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(backlogTaskRepository.findByIdAndBoardIdIn(taskId, listOf(boardId))).thenReturn(existing)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)
        whenever(categoryRepository.findByIdAndBoardId(category.id!!, boardId)).thenReturn(category)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()

        val result = service.archive(userId, taskId)!!

        assertEquals(TaskStatus.ARCHIVED, result.status)
        assertEquals(RecurrenceKind.WEEKLY, result.recurrence?.kind)
        assertEquals(LocalDate.parse("2026-09-15"), result.relevantFrom)
        verify(backlogTaskRepository, org.mockito.kotlin.times(1)).save(any<BacklogTaskEntity>())
    }

    @Test
    fun `saving a recurring task derives the deadline from the rule, ignoring the sent one`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val existing = taskEntity(categoryEntity(id = catId), id = taskId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existing)
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(existing.category)
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()

        val result = service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
            title = "Rent",
            categoryId = catId.toString(),
            deadline = "2030-01-01",
            relevantFrom = "2026-09-25",
            recurrence = rentRule,
        ))

        assertEquals(LocalDate.parse("2026-10-02"), result.deadline)
    }

    @Test
    fun `creating a recurring task without a date starts at the rule's first occurrence`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        stubSaveTask()
        stubToday("2026-09-13T08:00:00Z")

        val result = service.createTask(userId, boardId, CreateBacklogTaskRequest(
            title = "Rent",
            categoryId = catId.toString(),
            recurrence = rentRule,
        ))

        assertEquals(LocalDate.parse("2026-09-25"), result.relevantFrom)
        assertEquals(LocalDate.parse("2026-10-02"), result.deadline)
    }

    @Test
    fun `an invalid recurrence rule is rejected`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndBoardId(catId, boardId)).thenReturn(categoryEntity(id = catId))

        assertFailsWith<InvalidRecurrenceException> {
            service.createTask(userId, boardId, CreateBacklogTaskRequest(
                title = "Rent",
                categoryId = catId.toString(),
                recurrence = RecurrenceInput(kind = "MONTHLY", day = 40),
            ))
        }
    }

    @Test
    fun `unscheduleTask cancels upcoming slots and drops the planned rows for a scheduled task`() {
        val taskId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, lastScheduledInSessionId = sessionId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        stubSaveTask(existingEntity)

        service.unscheduleTask(userId, boardId, taskId)

        verify(taskCompletionCancellationService).cancelUpcomingSlots(userId, sessionId, taskId)
        verify(plannedTaskService).deleteTaskFromSession(sessionId, taskId)
        assertNull(existingEntity.lastScheduledInSessionId)
    }

    @Test
    fun `unscheduleTask on an unplanned task skips notification and planned-row cleanup`() {
        val taskId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(existingEntity)
        stubSaveTask(existingEntity)

        service.unscheduleTask(userId, boardId, taskId)

        verify(taskCompletionCancellationService, never()).cancelUpcomingSlots(any(), any(), any())
        verify(plannedTaskService, never()).deleteTaskFromSession(any(), any())
    }

    @Test
    fun `updateTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.updateTask(userId, boardId, taskId, UpdateBacklogTaskRequest(
                title = "Title",
                status = "todo",
                categoryId = UUID.randomUUID().toString(),
            ))
        }
    }

    // --- reorderTask ---

    @Test
    fun `reorderTask places task between two existing tasks`() {
        val taskA = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "A")
        val taskB = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "M")
        val taskC = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "Z")

        // Move taskC between taskA and taskB
        whenever(backlogTaskRepository.findByIdAndBoardId(taskC.id!!, boardId)).thenReturn(taskC)
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId))
            .thenReturn(listOf(taskA, taskB, taskC))
        stubSaveTask(taskC)

        service.reorderTask(userId, boardId, taskC.id!!, ReorderTaskRequest(
            afterId = taskA.id.toString(),
            beforeId = taskB.id.toString(),
        ))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        val newKey = captor.firstValue.sortKey!!
        assertTrue(newKey > "A", "new key '$newKey' should be > 'A'")
        assertTrue(newKey < "M", "new key '$newKey' should be < 'M'")
    }

    @Test
    fun `reorderTask moves task to beginning when afterId is null`() {
        val taskA = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "M")
        val taskB = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "Z")

        whenever(backlogTaskRepository.findByIdAndBoardId(taskB.id!!, boardId)).thenReturn(taskB)
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId))
            .thenReturn(listOf(taskA, taskB))
        stubSaveTask(taskB)

        service.reorderTask(userId, boardId, taskB.id!!, ReorderTaskRequest(
            afterId = null,
            beforeId = taskA.id.toString(),
        ))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        val newKey = captor.firstValue.sortKey!!
        assertTrue(newKey < "M", "new key '$newKey' should be before 'M'")
    }

    @Test
    fun `reorderTask moves task to end when beforeId is null`() {
        val taskA = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "A")
        val taskB = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "M")

        whenever(backlogTaskRepository.findByIdAndBoardId(taskA.id!!, boardId)).thenReturn(taskA)
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId))
            .thenReturn(listOf(taskA, taskB))
        stubSaveTask(taskA)

        service.reorderTask(userId, boardId, taskA.id!!, ReorderTaskRequest(
            afterId = taskB.id.toString(),
            beforeId = null,
        ))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        val newKey = captor.firstValue.sortKey!!
        assertTrue(newKey > "M", "new key '$newKey' should be after 'M'")
    }

    @Test
    fun `reorderTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.reorderTask(userId, boardId, taskId, ReorderTaskRequest(null, null))
        }
    }

    @Test
    fun `reorderTask throws when afterId references unknown task`() {
        val unknownId = UUID.randomUUID()
        val taskA = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "M")

        whenever(backlogTaskRepository.findByIdAndBoardId(taskA.id!!, boardId)).thenReturn(taskA)
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId))
            .thenReturn(listOf(taskA))

        assertFailsWith<NoSuchElementException> {
            service.reorderTask(userId, boardId, taskA.id!!, ReorderTaskRequest(
                afterId = unknownId.toString(),
                beforeId = null,
            ))
        }
    }

    // --- deleteTask ---

    @Test
    fun `deleteTask deletes the found entity`() {
        val taskId = UUID.randomUUID()
        val entity = taskEntity(categoryEntity(), id = taskId)
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(entity)

        service.deleteTask(userId, boardId, taskId)

        verify(backlogTaskRepository).delete(entity)
    }

    @Test
    fun `deleteTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.deleteTask(userId, boardId, taskId)
        }
    }

    // --- duplicateTask ---

    @Test
    fun `duplicateTask copies fields with a new TODO status and a copy suffix, dropping claim and plan stamp`() {
        val taskId = UUID.randomUUID()
        val source = taskEntity(categoryEntity(), id = taskId, title = "Write report").apply {
            status = TaskStatus.DONE
            url = "https://example.com"
            assigneeUserId = UUID.randomUUID()
            lastScheduledInSessionId = UUID.randomUUID()
        }
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(source)
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(boardId)).thenReturn(null)
        stubSaveTask()

        val result = service.duplicateTask(userId, boardId, taskId)

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals("Write report (copy)", saved.title?.toString(Charsets.UTF_8))
        assertEquals(TaskStatus.TODO, saved.status)
        assertEquals("https://example.com", saved.url)
        assertNull(saved.assigneeUserId)
        assertNull(saved.lastScheduledInSessionId)
        verify(taskChangeService).recordCreated(eq(boardId), eq(userId), any(), eq("Write report (copy)"), eq(TaskStatus.TODO))
        assertEquals("Write report (copy)", result.title)
    }

    @Test
    fun `duplicateTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.duplicateTask(userId, boardId, taskId)
        }
    }

    // --- moveTask ---

    private val targetBoardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c0")
    private val targetCategoryId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    private fun targetCategoryEntity(id: UUID = targetCategoryId, label: String = "Work") =
        BacklogTaskCategoryEntity().apply {
            this.id = id
            this.boardId = targetBoardId
            this.label = label
            this.swatchId = CategoryColor.SUNSHINE
        }

    @Test
    fun `moveTask re-homes the task into the chosen category and clears claim and plan stamp`() {
        val taskId = UUID.randomUUID()
        val source = taskEntity(categoryEntity(label = "Work"), id = taskId, title = "Ship it").apply {
            assigneeUserId = UUID.randomUUID()
            lastScheduledInSessionId = UUID.randomUUID()
        }
        val targetCat = targetCategoryEntity()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(source)
        whenever(categoryRepository.findByIdAndBoardId(targetCategoryId, targetBoardId)).thenReturn(targetCat)
        whenever(tagRepository.findAllByBoardId(targetBoardId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByBoardId(targetBoardId)).thenReturn(null)
        stubSaveTask()

        val result = service.moveTask(userId, boardId, taskId, targetBoardId, targetCategoryId)

        assertEquals(targetBoardId, source.boardId)
        assertEquals(targetCat, source.category)
        assertNull(source.assigneeUserId)
        assertNull(source.lastScheduledInSessionId)
        verify(taskChangeService).bumpWatermark(boardId)
        verify(taskChangeService).bumpWatermark(targetBoardId)
        verify(taskChangeService, never()).recordCreated(any(), any(), any(), any(), any())
        assertEquals(targetBoardId, result.boardId)
    }

    @Test
    fun `moveTask throws when the chosen category is not on the destination board`() {
        val taskId = UUID.randomUUID()
        val source = taskEntity(categoryEntity(label = "Work"), id = taskId, title = "Ship it")
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(source)
        whenever(categoryRepository.findByIdAndBoardId(targetCategoryId, targetBoardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.moveTask(userId, boardId, taskId, targetBoardId, targetCategoryId)
        }
    }

    @Test
    fun `moveTask rejects a move to the same board`() {
        assertFailsWith<SameBoardMoveException> {
            service.moveTask(userId, boardId, UUID.randomUUID(), boardId, targetCategoryId)
        }
    }

    @Test
    fun `moveTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.moveTask(userId, boardId, taskId, targetBoardId, targetCategoryId)
        }
    }

    // --- setAssignee / claim ---

    @Test
    fun `setAssignee sets the assignee, bumps the watermark, and records no change event`() {
        val taskId = UUID.randomUUID()
        val entity = taskEntity(categoryEntity(), id = taskId)
        val assignee = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(entity)
        whenever(boardMembershipService.isMember(assignee, boardId)).thenReturn(true)
        stubSaveTask()

        val result = service.setAssignee(userId, boardId, taskId, assignee)

        assertEquals(assignee, result.assigneeUserId)
        assertEquals(assignee, entity.assigneeUserId)
        verify(taskChangeService).bumpWatermark(boardId)
        verify(taskChangeService, never()).recordStatusChange(any(), any(), any(), any(), any(), any())
        verify(taskChangeService, never()).recordCreated(any(), any(), any(), any(), any())
    }

    @Test
    fun `setAssignee clears the assignee when target is null`() {
        val taskId = UUID.randomUUID()
        val entity = taskEntity(categoryEntity(), id = taskId).apply { assigneeUserId = UUID.randomUUID() }
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(entity)
        stubSaveTask()

        val result = service.setAssignee(userId, boardId, taskId, null)

        assertNull(result.assigneeUserId)
        verify(boardMembershipService, never()).isMember(any(), any())
    }

    @Test
    fun `setAssignee rejects a target who is not a member of the board`() {
        val taskId = UUID.randomUUID()
        val entity = taskEntity(categoryEntity(), id = taskId)
        val stranger = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(entity)
        whenever(boardMembershipService.isMember(stranger, boardId)).thenReturn(false)

        assertFailsWith<AssigneeNotMemberException> {
            service.setAssignee(userId, boardId, taskId, stranger)
        }
        verify(taskChangeService, never()).bumpWatermark(any())
    }

    @Test
    fun `setAssignee throws when the task is not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndBoardId(taskId, boardId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.setAssignee(userId, boardId, taskId, null)
        }
    }

    @Test
    fun `stampPlanningSession claims unassigned tasks for the scheduling user but preserves existing claims`() {
        val sessionId = UUID.randomUUID()
        val unassigned = taskEntity(categoryEntity(), id = UUID.randomUUID())
        val other = UUID.randomUUID()
        val claimed = taskEntity(categoryEntity(), id = UUID.randomUUID()).apply { assigneeUserId = other }
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(backlogTaskRepository.findAllByBoardIdInAndIdIn(listOf(boardId), listOf(unassigned.id!!, claimed.id!!)))
            .thenReturn(listOf(unassigned, claimed))

        service.stampPlanningSession(userId, listOf(unassigned.id!!, claimed.id!!), sessionId)

        assertEquals(userId, unassigned.assigneeUserId)
        assertEquals(other, claimed.assigneeUserId)
        assertEquals(sessionId, unassigned.lastScheduledInSessionId)
        assertEquals(sessionId, claimed.lastScheduledInSessionId)
        verify(taskChangeService).bumpWatermark(boardId)
    }

    // --- helpers ---

    private fun stubSaveTask(existing: BacklogTaskEntity? = null) {
        whenever(backlogTaskRepository.save(any<BacklogTaskEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskEntity).also { if (it.id == null) it.id = UUID.randomUUID() }
        }
    }

    private fun categoryEntity(
        id: UUID = UUID.randomUUID(),
        label: String = "Work",
        swatchId: CategoryColor = CategoryColor.SUNSHINE,
    ) = BacklogTaskCategoryEntity().apply {
        this.id = id
        this.boardId = this@BacklogTaskServiceTest.boardId
        this.label = label
        this.swatchId = swatchId
    }

    private fun tagEntity(
        id: UUID = UUID.randomUUID(),
        label: String = "tag",
        colorId: TagColor = TagColor.SAGE,
    ) = BacklogTaskTagEntity().apply {
        this.id = id
        this.boardId = this@BacklogTaskServiceTest.boardId
        this.label = label
        this.colorId = colorId
    }

    private fun taskEntity(
        category: BacklogTaskCategoryEntity,
        id: UUID = UUID.randomUUID(),
        title: String = "Task",
        sortKey: String = SortKeyGenerator.INITIAL,
        lastScheduledInSessionId: UUID? = null,
        hiddenFromAssistant: Boolean = false,
    ) = BacklogTaskEntity().apply {
        this.id = id
        this.boardId = this@BacklogTaskServiceTest.boardId
        this.title = title.toByteArray(Charsets.UTF_8)
        this.status = TaskStatus.TODO
        this.category = category
        this.tags = mutableSetOf()
        this.sortKey = sortKey
        this.createdAt = Instant.now()
        this.updatedAt = null
        this.lastScheduledInSessionId = lastScheduledInSessionId
        this.hiddenFromAssistant = hiddenFromAssistant
    }
}
