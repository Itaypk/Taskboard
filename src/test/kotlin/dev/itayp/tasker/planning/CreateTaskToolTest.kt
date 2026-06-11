package dev.itayp.tasker.planning

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateTaskToolTest {

    private val backlogTaskService: BacklogTaskService = mock()
    private val boardMembershipService: BoardMembershipService = mock()
    private val context = PlanningToolContext()
    private val objectMapper = jacksonObjectMapper()
    private val tool = CreateTaskTool(backlogTaskService, boardMembershipService, context, objectMapper)

    private val defaultBoardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    @Test
    fun `persists the task with category and tags on the default board and returns its id`() {
        val userId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val createdId = UUID.randomUUID()
        context.begin(userId, ZoneOffset.UTC)
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(defaultBoardId)
        whenever(backlogTaskService.createTask(eq(userId), eq(defaultBoardId), any()))
            .thenReturn(backlogTask(createdId, "Call the dentist", categoryId))

        val args = """
            {
              "title": "Call the dentist",
              "category_id": "$categoryId",
              "priority": "high",
              "tags": [{"label": "health", "color_id": "rose"}]
            }
        """.trimIndent()
        val result = tool.execute(args)
        context.clear()

        val captor = argumentCaptor<CreateBacklogTaskRequest>()
        verify(backlogTaskService).createTask(eq(userId), eq(defaultBoardId), captor.capture())
        assertEquals("Call the dentist", captor.firstValue.title)
        assertEquals(categoryId.toString(), captor.firstValue.categoryId)
        assertEquals("high", captor.firstValue.priority)
        assertEquals(1, captor.firstValue.tags.size)
        assertEquals("health", captor.firstValue.tags.first().label)
        assertTrue(result.contains(createdId.toString()))
    }

    @Test
    fun `falls back to a valid color when an unexpected color_id is given`() {
        val userId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        context.begin(userId, ZoneOffset.UTC)
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(defaultBoardId)
        whenever(backlogTaskService.createTask(eq(userId), eq(defaultBoardId), any()))
            .thenReturn(backlogTask(UUID.randomUUID(), "x", categoryId))

        tool.execute("""{"title":"x","category_id":"$categoryId","tags":[{"label":"misc","color_id":"chartreuse"}]}""")
        context.clear()

        val captor = argumentCaptor<CreateBacklogTaskRequest>()
        verify(backlogTaskService).createTask(eq(userId), eq(defaultBoardId), captor.capture())
        val color = captor.firstValue.tags.first().colorId
        assertTrue(TagColor.entries.any { it.name.equals(color, ignoreCase = true) }, "expected a valid color, got $color")
    }

    @Test
    fun `returns a structured error when creation fails`() {
        val userId = UUID.randomUUID()
        context.begin(userId, ZoneOffset.UTC)
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(defaultBoardId)
        whenever(backlogTaskService.createTask(eq(userId), eq(defaultBoardId), any()))
            .thenThrow(NoSuchElementException("Category not found"))

        val result = tool.execute("""{"title":"x","category_id":"nope"}""")
        context.clear()

        assertTrue(result.contains("\"error\""))
    }

    @Test
    fun `targets an explicit board when board_id is given`() {
        val userId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val otherBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        context.begin(userId, ZoneOffset.UTC)
        whenever(backlogTaskService.createTask(eq(userId), eq(otherBoardId), any()))
            .thenReturn(backlogTask(UUID.randomUUID(), "x", categoryId))

        tool.execute("""{"title":"x","category_id":"$categoryId","board_id":"$otherBoardId"}""")
        context.clear()

        verify(backlogTaskService).createTask(eq(userId), eq(otherBoardId), any())
    }

    private fun backlogTask(id: UUID, title: String, categoryId: UUID) = BacklogTask(
        id = id,
        boardId = UUID.randomUUID(),
        assigneeUserId = null,
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = TaskStatus.TODO,
        category = BacklogTaskCategory(categoryId, UUID.randomUUID(), "Health", CategoryColor.PEACH),
        tags = emptySet(),
        sortKey = "a",
        createdAt = Instant.now(),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
    )
}
