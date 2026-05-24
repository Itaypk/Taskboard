package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.ReorderTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
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
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var clock: Clock

    @InjectMocks private lateinit var service: BacklogTaskService

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // --- getAllTasksForUser ---

    @Test
    fun `getAllTasksForUser returns tasks mapped to domain`() {
        val category = categoryEntity()
        val entity = taskEntity(category, title = "My Task")
        whenever(backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId)).thenReturn(listOf(entity))

        val result = service.getAllTasksForUser(userId)

        assertEquals(1, result.size)
        assertEquals("My Task", result[0].title)
        assertEquals(TaskStatus.TODO, result[0].status)
    }

    // --- createTask ---

    @Test
    fun `createTask maps priority and status from lowercase strings`() {
        val catId = UUID.randomUUID()
        val cat = categoryEntity(id = catId)
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(cat)
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(
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
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertNotNull(captor.firstValue.sortKey)
    }

    @Test
    fun `createTask assigns a sort key after the current max`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn("M")
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertTrue(captor.firstValue.sortKey!! > "M", "new sort key should be after 'M'")
    }

    @Test
    fun `createTask parses deadline string to LocalDate`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(
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
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))

        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(captor.capture())
        assertNotNull(captor.firstValue.createdAt)
        assertNull(captor.firstValue.updatedAt)
    }

    @Test
    fun `createTask throws when category not found`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.createTask(userId, CreateBacklogTaskRequest(title = "Task", categoryId = catId.toString()))
        }
    }

    @Test
    fun `createTask creates new tag when none matches`() {
        val catId = UUID.randomUUID()
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn(null)
        whenever(tagRepository.save(any<BacklogTaskTagEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskTagEntity).also { it.id = UUID.randomUUID() }
        }
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(
            title = "Task",
            categoryId = catId.toString(),
            tags = listOf(TagInput(label = "urgent", colorId = "coral")),
        ))

        val captor = argumentCaptor<BacklogTaskTagEntity>()
        verify(tagRepository).save(captor.capture())
        assertEquals("urgent", captor.firstValue.label)
        assertEquals(TagColor.CORAL, captor.firstValue.colorId)
    }

    @Test
    fun `createTask reuses existing tag when label and color match`() {
        val catId = UUID.randomUUID()
        val existingTag = tagEntity(label = "urgent", colorId = TagColor.CORAL)
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(listOf(existingTag))
        whenever(backlogTaskRepository.findMaxSortKeyByUserId(userId)).thenReturn(null)
        stubSaveTask()

        service.createTask(userId, CreateBacklogTaskRequest(
            title = "Task",
            categoryId = catId.toString(),
            tags = listOf(TagInput(label = "urgent", colorId = "coral")),
        ))

        verify(tagRepository, never()).save(any())
    }

    // --- updateTask ---

    @Test
    fun `updateTask updates task fields and sets updatedAt`() {
        val taskId = UUID.randomUUID()
        val catId = UUID.randomUUID()
        val existingEntity = taskEntity(categoryEntity(), id = taskId, title = "Old Title")
        whenever(backlogTaskRepository.findByIdAndUserId(taskId, userId)).thenReturn(existingEntity)
        whenever(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(categoryEntity(id = catId))
        whenever(tagRepository.findAllByUserId(userId)).thenReturn(emptyList())
        stubSaveTask(existingEntity)

        service.updateTask(userId, taskId, UpdateBacklogTaskRequest(
            title = "New Title",
            status = "done",
            categoryId = catId.toString(),
        ))

        assertEquals("New Title", existingEntity.title)
        assertEquals(TaskStatus.DONE, existingEntity.status)
        assertNotNull(existingEntity.updatedAt)
    }

    @Test
    fun `updateTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndUserId(taskId, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.updateTask(userId, taskId, UpdateBacklogTaskRequest(
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
        whenever(backlogTaskRepository.findByIdAndUserId(taskC.id!!, userId)).thenReturn(taskC)
        whenever(backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId))
            .thenReturn(listOf(taskA, taskB, taskC))
        stubSaveTask(taskC)

        service.reorderTask(userId, taskC.id!!, ReorderTaskRequest(
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

        whenever(backlogTaskRepository.findByIdAndUserId(taskB.id!!, userId)).thenReturn(taskB)
        whenever(backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId))
            .thenReturn(listOf(taskA, taskB))
        stubSaveTask(taskB)

        service.reorderTask(userId, taskB.id!!, ReorderTaskRequest(
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

        whenever(backlogTaskRepository.findByIdAndUserId(taskA.id!!, userId)).thenReturn(taskA)
        whenever(backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId))
            .thenReturn(listOf(taskA, taskB))
        stubSaveTask(taskA)

        service.reorderTask(userId, taskA.id!!, ReorderTaskRequest(
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
        whenever(backlogTaskRepository.findByIdAndUserId(taskId, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.reorderTask(userId, taskId, ReorderTaskRequest(null, null))
        }
    }

    @Test
    fun `reorderTask throws when afterId references unknown task`() {
        val unknownId = UUID.randomUUID()
        val taskA = taskEntity(categoryEntity(), id = UUID.randomUUID(), sortKey = "M")

        whenever(backlogTaskRepository.findByIdAndUserId(taskA.id!!, userId)).thenReturn(taskA)
        whenever(backlogTaskRepository.findAllByUserIdOrderBySortKeyAsc(userId))
            .thenReturn(listOf(taskA))

        assertFailsWith<NoSuchElementException> {
            service.reorderTask(userId, taskA.id!!, ReorderTaskRequest(
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
        whenever(backlogTaskRepository.findByIdAndUserId(taskId, userId)).thenReturn(entity)

        service.deleteTask(userId, taskId)

        verify(backlogTaskRepository).delete(entity)
    }

    @Test
    fun `deleteTask throws when task not found`() {
        val taskId = UUID.randomUUID()
        whenever(backlogTaskRepository.findByIdAndUserId(taskId, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.deleteTask(userId, taskId)
        }
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
        this.userId = this@BacklogTaskServiceTest.userId
        this.label = label
        this.swatchId = swatchId
    }

    private fun tagEntity(
        id: UUID = UUID.randomUUID(),
        label: String = "tag",
        colorId: TagColor = TagColor.SAGE,
    ) = BacklogTaskTagEntity().apply {
        this.id = id
        this.userId = this@BacklogTaskServiceTest.userId
        this.label = label
        this.colorId = colorId
    }

    private fun taskEntity(
        category: BacklogTaskCategoryEntity,
        id: UUID = UUID.randomUUID(),
        title: String = "Task",
        sortKey: String = SortKeyGenerator.INITIAL,
    ) = BacklogTaskEntity().apply {
        this.id = id
        this.userId = this@BacklogTaskServiceTest.userId
        this.title = title
        this.status = TaskStatus.TODO
        this.category = category
        this.tags = mutableSetOf()
        this.sortKey = sortKey
        this.createdAt = Instant.now()
        this.updatedAt = null
    }
}
