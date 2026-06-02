package dev.itayp.tasker.planning

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.service.BacklogTaskService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateTaskToolTest {

    private val backlogTaskService: BacklogTaskService = mock()
    private val context = PlanningToolContext()
    private val objectMapper = jacksonObjectMapper()
    private val tool = UpdateTaskTool(backlogTaskService, context, objectMapper)

    private val userId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()
    private val tagId = UUID.randomUUID()

    @Test
    fun `partial update changes only the provided field and keeps the rest`() {
        stubCurrentTask()
        val captor = executeAndCapture("""{"task_id":"$taskId","title":"Renamed"}""")

        assertEquals("Renamed", captor.title)
        // Untouched fields round-trip from the current task.
        assertEquals("high", captor.priority)
        assertEquals("2026-06-10", captor.deadline)
        assertEquals(categoryId.toString(), captor.categoryId)
        assertEquals("todo", captor.status)
        assertEquals(1, captor.tags.size)
        assertEquals(tagId.toString(), captor.tags.first().id)
        assertEquals("health", captor.tags.first().label)
    }

    @Test
    fun `marks the task done`() {
        stubCurrentTask()
        val captor = executeAndCapture("""{"task_id":"$taskId","status":"done"}""")

        assertEquals("done", captor.status)
        assertEquals("Old title", captor.title)
    }

    @Test
    fun `archives the task`() {
        stubCurrentTask()
        whenever(backlogTaskService.updateTask(eq(userId), eq(taskId), any()))
            .thenReturn(currentTask().copy(status = TaskStatus.ARCHIVED))

        context.begin(userId, ZoneOffset.UTC)
        val result = tool.execute("""{"task_id":"$taskId","status":"archived"}""")
        context.clear()

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(taskId), captor.capture())
        assertEquals("archived", captor.firstValue.status)
        assertTrue(result.contains("archived"))
    }

    @Test
    fun `present empty string clears a nullable field`() {
        stubCurrentTask()
        val captor = executeAndCapture("""{"task_id":"$taskId","deadline":""}""")

        assertEquals("", captor.deadline)
    }

    @Test
    fun `tags array replaces the existing tag set`() {
        stubCurrentTask()
        val reusedId = UUID.randomUUID()
        val captor = executeAndCapture(
            """{"task_id":"$taskId","tags":[{"id":"$reusedId","label":"new","color_id":"sage"}]}""",
        )

        assertEquals(1, captor.tags.size)
        assertEquals(reusedId.toString(), captor.tags.first().id)
        assertEquals("new", captor.tags.first().label)
        assertEquals("sage", captor.tags.first().colorId)
    }

    @Test
    fun `empty tags array removes all tags`() {
        stubCurrentTask()
        val captor = executeAndCapture("""{"task_id":"$taskId","tags":[]}""")

        assertTrue(captor.tags.isEmpty())
    }

    @Test
    fun `returns a structured error and does not update when the task is missing`() {
        whenever(backlogTaskService.getTaskById(eq(userId), eq(taskId))).thenReturn(null)

        context.begin(userId, ZoneOffset.UTC)
        val result = tool.execute("""{"task_id":"$taskId","title":"x"}""")
        context.clear()

        assertTrue(result.contains("\"error\""))
        verify(backlogTaskService, never()).updateTask(any(), any(), any())
    }

    @Test
    fun `returns a structured error on unparseable arguments`() {
        context.begin(userId, ZoneOffset.UTC)
        val result = tool.execute("{not json")
        context.clear()

        assertTrue(result.contains("\"error\""))
        verify(backlogTaskService, never()).getTaskById(any(), any())
    }

    @Test
    fun `returns a structured error when the service rejects the update`() {
        stubCurrentTask()
        whenever(backlogTaskService.updateTask(eq(userId), eq(taskId), any()))
            .thenThrow(NoSuchElementException("Category not found"))

        context.begin(userId, ZoneOffset.UTC)
        val result = tool.execute("""{"task_id":"$taskId","category_id":"nope"}""")
        context.clear()

        assertTrue(result.contains("\"error\""))
    }

    // -- helpers ----------------------------------------------------------------

    /** Executes the tool inside a context scope and returns the captured request. */
    private fun executeAndCapture(args: String): UpdateBacklogTaskRequest {
        whenever(backlogTaskService.updateTask(eq(userId), eq(taskId), any()))
            .thenReturn(currentTask())

        context.begin(userId, ZoneOffset.UTC)
        tool.execute(args)
        context.clear()

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(taskId), captor.capture())
        return captor.firstValue
    }

    private fun stubCurrentTask() {
        whenever(backlogTaskService.getTaskById(eq(userId), eq(taskId))).thenReturn(currentTask())
    }

    private fun currentTask() = BacklogTask(
        id = taskId,
        userId = userId,
        title = "Old title",
        description = "notes",
        url = null,
        priority = TaskPriority.HIGH,
        deadline = LocalDate.parse("2026-06-10"),
        estimatedMinutes = 30,
        status = TaskStatus.TODO,
        category = BacklogTaskCategory(categoryId, userId, "Health", CategoryColor.PEACH),
        tags = setOf(BacklogTaskTag(tagId, userId, "health", TagColor.ROSE, null)),
        sortKey = "a",
        createdAt = Instant.now(),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
    )
}
