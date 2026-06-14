package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.AssigneeNotMemberException
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardAccessDeniedException
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.SameBoardMoveException
import dev.itayp.tasker.service.SortKeyGenerator
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.util.UUID

@WebMvcTest(BacklogTaskController::class)
@Import(SecurityConfiguration::class)
class BacklogTaskControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var backlogTaskService: BacklogTaskService

    @MockitoBean
    lateinit var backlogTaskChangeService: BacklogTaskChangeService

    @MockitoBean
    lateinit var boardMembershipService: BoardMembershipService

    @MockitoBean
    lateinit var clock: Clock

    private val fixedNow = Instant.parse("2026-05-19T10:00:00Z")

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private val basePath = "/api/v1/boards/$boardId/tasks"

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun aTask(id: UUID = taskId, title: String = "Test Task") = BacklogTask(
        id = id,
        boardId = boardId,
        assigneeUserId = null,
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = TaskStatus.TODO,
        category = BacklogTaskCategory(categoryId, boardId, "Work", CategoryColor.SUNSHINE),
        tags = emptySet(),
        sortKey = SortKeyGenerator.INITIAL,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
    )

    @Test
    fun `GET tasks defaults to TODO filter`() {
        whenever(backlogTaskService.getTasks(userId, boardId, TaskStatus.TODO)).thenReturn(listOf(aTask()))

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].title").value("Test Task"))
            .andExpect(jsonPath("$[0].status").value("todo"))
            .andExpect(jsonPath("$[0].categoryId").value(categoryId.toString()))
    }

    @Test
    fun `GET tasks with status=done filters to DONE`() {
        whenever(backlogTaskService.getTasks(userId, boardId, TaskStatus.DONE)).thenReturn(emptyList())

        mockMvc.perform(get("$basePath?status=done").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET tasks with status=all passes null filter`() {
        whenever(backlogTaskService.getTasks(userId, boardId, null)).thenReturn(listOf(aTask()))

        mockMvc.perform(get("$basePath?status=all").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].title").value("Test Task"))
    }

    @Test
    fun `GET tasks with invalid status returns 400`() {
        mockMvc.perform(get("$basePath?status=bogus").with(authentication(auth)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET tasks returns 200 with empty list`() {
        whenever(backlogTaskService.getTasks(userId, boardId, TaskStatus.TODO)).thenReturn(emptyList())

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET tasks unauthenticated returns 401`() {
        mockMvc.perform(get(basePath))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET tasks on a board the user is not a member of returns 403`() {
        whenever(backlogTaskService.getTasks(userId, boardId, TaskStatus.TODO))
            .thenThrow(BoardAccessDeniedException(userId, boardId))

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST tasks returns 201 with created task`() {
        whenever(backlogTaskService.createTask(eq(userId), eq(boardId), any())).thenReturn(aTask(title = "New Task"))

        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"New Task","status":"todo","categoryId":"$categoryId","tags":[]}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("New Task"))
            .andExpect(jsonPath("$.id").exists())
    }

    @Test
    fun `POST tasks with an invalid link returns 400 with a field-specific message`() {
        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"New Task","status":"todo","categoryId":"$categoryId","url":"example.com","tags":[]}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Link must start with http:// or https://"))
            .andExpect(jsonPath("$.fields[0].field").value("url"))
    }

    @Test
    fun `POST duplicate returns 201 with the copy`() {
        whenever(backlogTaskService.duplicateTask(userId, boardId, taskId)).thenReturn(aTask(title = "Test Task (copy)"))

        mockMvc.perform(post("$basePath/$taskId/duplicate").with(authentication(auth)).with(csrf()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Test Task (copy)"))
    }

    @Test
    fun `POST duplicate returns 404 when task not found`() {
        whenever(backlogTaskService.duplicateTask(userId, boardId, taskId))
            .thenThrow(NoSuchElementException("not found"))

        mockMvc.perform(post("$basePath/$taskId/duplicate").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST move returns 200 with the moved task`() {
        val targetBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000c0")
        whenever(backlogTaskService.moveTask(userId, boardId, taskId, targetBoardId)).thenReturn(aTask())

        mockMvc.perform(
            post("$basePath/$taskId/move")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetBoardId":"$targetBoardId"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(taskId.toString()))
    }

    @Test
    fun `POST move to the same board returns 400`() {
        whenever(backlogTaskService.moveTask(eq(userId), eq(boardId), eq(taskId), any()))
            .thenThrow(SameBoardMoveException())

        mockMvc.perform(
            post("$basePath/$taskId/move")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetBoardId":"$boardId"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PUT tasks-id returns 200 with updated task`() {
        whenever(backlogTaskService.updateTask(eq(userId), eq(boardId), eq(taskId), any()))
            .thenReturn(aTask(title = "Updated"))

        mockMvc.perform(
            put("$basePath/$taskId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Updated","status":"todo","categoryId":"$categoryId","tags":[]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Updated"))
    }

    @Test
    fun `PUT tasks-id returns 404 when task not found`() {
        whenever(backlogTaskService.updateTask(any(), any(), any(), any()))
            .thenThrow(NoSuchElementException("not found"))

        mockMvc.perform(
            put("$basePath/$taskId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"X","status":"todo","categoryId":"$categoryId","tags":[]}""")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PUT assignee returns 200 with the updated task`() {
        val assignee = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        whenever(backlogTaskService.setAssignee(userId, boardId, taskId, assignee))
            .thenReturn(aTask().copy(assigneeUserId = assignee))

        mockMvc.perform(
            put("$basePath/$taskId/assignee")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$assignee"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assigneeUserId").value(assignee.toString()))
    }

    @Test
    fun `PUT assignee with null clears the assignee`() {
        whenever(backlogTaskService.setAssignee(userId, boardId, taskId, null)).thenReturn(aTask())

        mockMvc.perform(
            put("$basePath/$taskId/assignee")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":null}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assigneeUserId").doesNotExist())
    }

    @Test
    fun `PUT assignee with a malformed userId returns 400`() {
        mockMvc.perform(
            put("$basePath/$taskId/assignee")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"not-a-uuid"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PUT assignee with a non-member target returns 400`() {
        val stranger = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        whenever(backlogTaskService.setAssignee(userId, boardId, taskId, stranger))
            .thenThrow(AssigneeNotMemberException(stranger, boardId))

        mockMvc.perform(
            put("$basePath/$taskId/assignee")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$stranger"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PUT assignee on a board the user is not a member of returns 403`() {
        val target = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        whenever(backlogTaskService.setAssignee(userId, boardId, taskId, target))
            .thenThrow(BoardAccessDeniedException(userId, boardId))

        mockMvc.perform(
            put("$basePath/$taskId/assignee")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"$target"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `DELETE tasks-id returns 204`() {
        mockMvc.perform(delete("$basePath/$taskId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE tasks-id returns 404 when task not found`() {
        doThrow(NoSuchElementException("not found")).whenever(backlogTaskService).deleteTask(any(), any(), any())

        mockMvc.perform(delete("$basePath/$taskId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PATCH tasks-id-reorder returns 200 with updated task`() {
        whenever(backlogTaskService.reorderTask(eq(userId), eq(boardId), eq(taskId), any())).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId/reorder")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"afterId":null,"beforeId":null}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(taskId.toString()))
            .andExpect(jsonPath("$.sortKey").exists())
    }

    @Test
    fun `PATCH tasks-id-reorder returns 404 when task not found`() {
        whenever(backlogTaskService.reorderTask(any(), any(), any(), any()))
            .thenThrow(NoSuchElementException("not found"))

        mockMvc.perform(
            patch("$basePath/$taskId/reorder")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"afterId":null,"beforeId":null}""")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET tasks-has-changes returns hasChanges=true when events exist`() {
        val since = "2026-05-19T09:00:00Z"
        whenever(clock.instant()).thenReturn(fixedNow)
        whenever(backlogTaskChangeService.changedSince(eq(boardId), any())).thenReturn(true)

        mockMvc.perform(get("$basePath/has-changes?since=$since").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasChanges").value(true))
            .andExpect(jsonPath("$.checkedAt").value(fixedNow.toString()))
    }

    @Test
    fun `GET tasks-has-changes returns hasChanges=false when no events`() {
        val since = "2026-05-19T09:00:00Z"
        whenever(clock.instant()).thenReturn(fixedNow)
        whenever(backlogTaskChangeService.changedSince(eq(boardId), any())).thenReturn(false)

        mockMvc.perform(get("$basePath/has-changes?since=$since").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasChanges").value(false))
    }

    @Test
    fun `GET tasks-has-changes returns 400 for invalid since parameter`() {
        mockMvc.perform(get("$basePath/has-changes?since=not-a-date").with(authentication(auth)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET tasks-has-changes enforces board membership`() {
        whenever(boardMembershipService.requireMember(userId, boardId))
            .thenThrow(BoardAccessDeniedException(userId, boardId))

        mockMvc.perform(get("$basePath/has-changes?since=2026-05-19T09:00:00Z").with(authentication(auth)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `GET tasks-has-changes unauthenticated returns 401`() {
        mockMvc.perform(get("$basePath/has-changes?since=2026-05-19T09:00:00Z"))
            .andExpect(status().isUnauthorized)
    }
}
