package dev.itayp.tasker.external

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.RecurrenceKind
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskRecurrence
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.security.ApiTokenAuthenticationFilter
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.ApiTokenService
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.BoardService
import dev.itayp.tasker.service.SortKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Slice test for the external task API. The behavior that matters most here is the PATCH merge:
 * the SPA's PUT is a full replace, and the whole point of this surface is that a partial update
 * cannot silently destroy fields the caller didn't mention.
 */
@WebMvcTest(ExternalTaskController::class)
@Import(SecurityConfiguration::class)
class ExternalTaskControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var backlogTaskService: BacklogTaskService
    @MockitoBean lateinit var boardMembershipService: BoardMembershipService
    @MockitoBean lateinit var boardService: BoardService
    @MockitoBean lateinit var categoryService: BacklogTaskCategoryService

    /**
     * Its presence is what makes [SecurityConfiguration] build a token filter for this chain, so
     * the slice matches production wiring. No test here sends a bearer token — authentication
     * comes from the `authentication(...)` post-processor and the filter passes through. Real
     * token auth is covered by `ExternalApiSecurityIntegrationTest`.
     */
    @MockitoBean lateinit var apiTokenService: ApiTokenService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val tagId = UUID.fromString("00000000-0000-0000-0000-000000000004")

    private val basePath = "/api/external/v1/tasks"

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(
            SimpleGrantedAuthority("ROLE_USER"),
            SimpleGrantedAuthority(ApiTokenAuthenticationFilter.EXTERNAL_READ),
            SimpleGrantedAuthority(ApiTokenAuthenticationFilter.EXTERNAL_WRITE),
        ),
    )

    private val readOnlyAuth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(
            SimpleGrantedAuthority("ROLE_USER"),
            SimpleGrantedAuthority(ApiTokenAuthenticationFilter.EXTERNAL_READ),
        ),
    )

    private fun aTask(
        title: String = "Renew passport",
        description: String? = "Book an appointment first",
        priority: TaskPriority? = TaskPriority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO,
        tags: Set<BacklogTaskTag> = setOf(BacklogTaskTag(tagId, boardId, "errands", TagColor.SAGE, null)),
    ) = BacklogTask(
        id = taskId,
        boardId = boardId,
        assigneeUserId = null,
        title = title,
        description = description,
        url = null,
        priority = priority,
        deadline = java.time.LocalDate.parse("2026-09-01"),
        estimatedMinutes = 30,
        status = status,
        category = BacklogTaskCategory(categoryId, boardId, "Work", CategoryColor.SUNSHINE),
        tags = tags,
        sortKey = SortKeyGenerator.INITIAL,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
    )

    private fun stubBoards() {
        whenever(boardService.listBoardsForUser(userId)).thenReturn(
            listOf(
                BoardSummary(
                    id = boardId,
                    name = "Personal",
                    role = BoardRole.OWNER,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    memberCount = 1,
                    mascot = "default",
                )
            )
        )
    }

    // --- Reads ---

    @Test
    fun `GET tasks spans every board and reports the board name`() {
        stubBoards()
        whenever(backlogTaskService.getTasksAcrossBoards(userId, TaskStatus.TODO)).thenReturn(listOf(aTask()))

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.tasks[0].title").value("Renew passport"))
            .andExpect(jsonPath("$.tasks[0].boardName").value("Personal"))
            .andExpect(jsonPath("$.tasks[0].status").value("todo"))
            .andExpect(jsonPath("$.tasks[0].tags[0].label").value("errands"))
    }

    @Test
    fun `GET tasks filters by free-text query across title and description`() {
        stubBoards()
        whenever(backlogTaskService.getTasksAcrossBoards(userId, TaskStatus.TODO)).thenReturn(
            listOf(aTask(title = "Renew passport"), aTask(title = "Buy milk", description = null))
        )

        mockMvc.perform(get("$basePath?q=passport").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.tasks[0].title").value("Renew passport"))

        // Matches on description too, not just title.
        mockMvc.perform(get("$basePath?q=appointment").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
    }

    @Test
    fun `GET tasks reports truncation so a caller knows to narrow the query`() {
        stubBoards()
        whenever(backlogTaskService.getTasksAcrossBoards(userId, TaskStatus.TODO))
            .thenReturn(listOf(aTask(), aTask(title = "Second")))

        mockMvc.perform(get("$basePath?limit=1").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.truncated").value(true))
    }

    @Test
    fun `an unknown status is a 400 that names the allowed values`() {
        mockMvc.perform(get("$basePath?status=finished").with(authentication(auth)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("todo")))
    }

    @Test
    fun `GET task by id resolves across boards`() {
        stubBoards()
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())

        mockMvc.perform(get("$basePath/$taskId").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(taskId.toString()))
            .andExpect(jsonPath("$.categoryLabel").value("Work"))
    }

    @Test
    fun `GET an unknown task is a 404 carrying a problem detail`() {
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(null)

        mockMvc.perform(get("$basePath/$taskId").with(authentication(auth)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value(containsString(taskId.toString())))
    }

    // --- Error shape: every failure is an RFC 7807 body an AI caller can act on, never an empty response ---

    @Test
    fun `a task id that is not a UUID is a 400 naming the parameter`() {
        mockMvc.perform(get("$basePath/not-a-uuid").with(authentication(auth)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("'id'")))
    }

    @Test
    fun `POST without a title is a 400 naming the missing field`() {
        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("'title'")))
    }

    @Test
    fun `POST with a blank title is a 400 naming the field`() {
        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"   "}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("'title'")))
    }

    @Test
    fun `POST with malformed JSON is a 400 with a detail`() {
        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title": """)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Malformed request body"))
            .andExpect(jsonPath("$.detail").value(containsString("JSON object")))
    }

    @Test
    fun `PATCH with a body field of the wrong type is a 400 with a detail`() {
        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"estimatedMinutes":"soon"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("JSON object")))
    }

    // --- PATCH merge semantics: the reason this API exists ---

    @Test
    fun `PATCH leaves untouched fields alone`() {
        stubBoards()
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())
        whenever(categoryService.getCategories(userId, boardId)).thenReturn(emptyList())
        whenever(backlogTaskService.updateTask(eq(userId), eq(boardId), eq(taskId), any()))
            .thenReturn(aTask(priority = TaskPriority.HIGH))

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"priority":"high"}""")
        ).andExpect(status().isOk)

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(boardId), eq(taskId), captor.capture())
        val sent = captor.firstValue

        assertThat(sent.priority).isEqualTo("high")
        // Everything the request didn't mention must survive — this is what PUT would have wiped.
        assertThat(sent.title).isEqualTo("Renew passport")
        assertThat(sent.description).isEqualTo("Book an appointment first")
        assertThat(sent.deadline).isEqualTo("2026-09-01")
        assertThat(sent.estimatedMinutes).isEqualTo(30)
        assertThat(sent.status).isEqualTo("todo")
        assertThat(sent.categoryId).isEqualTo(categoryId.toString())
        assertThat(sent.tags).hasSize(1)
        assertThat(sent.tags[0].label).isEqualTo("errands")
    }

    @Test
    fun `PATCH clear unsets only the named field`() {
        stubBoards()
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())
        whenever(categoryService.getCategories(userId, boardId)).thenReturn(emptyList())
        whenever(backlogTaskService.updateTask(eq(userId), eq(boardId), eq(taskId), any())).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clear":["deadline"]}""")
        ).andExpect(status().isOk)

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(boardId), eq(taskId), captor.capture())

        assertThat(captor.firstValue.deadline).isNull()
        assertThat(captor.firstValue.description).isEqualTo("Book an appointment first")
        assertThat(captor.firstValue.estimatedMinutes).isEqualTo(30)
    }

    @Test
    fun `PATCH with an explicit null leaves the field unchanged rather than clearing it`() {
        stubBoards()
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())
        whenever(categoryService.getCategories(userId, boardId)).thenReturn(emptyList())
        whenever(backlogTaskService.updateTask(eq(userId), eq(boardId), eq(taskId), any())).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"description":null}""")
        ).andExpect(status().isOk)

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(boardId), eq(taskId), captor.capture())

        // Absent and explicit-null are indistinguishable, so both mean "leave alone".
        assertThat(captor.firstValue.description).isEqualTo("Book an appointment first")
    }

    @Test
    fun `PATCH that doesn't mention recurrence carries the current rule forward`() {
        stubBoards()
        val recurring = aTask().copy(recurrence = TaskRecurrence(RecurrenceKind.MONTHLY, day = 25, dueWithinDays = 7))
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(recurring)
        whenever(backlogTaskService.updateTask(eq(userId), eq(boardId), eq(taskId), any())).thenReturn(recurring)

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Pay rent"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recurrence.kind").value("MONTHLY"))

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(boardId), eq(taskId), captor.capture())
        // The service PUT contract is a full replace: dropping this would silently stop the recurrence.
        assertThat(captor.firstValue.recurrence?.kind).isEqualTo("MONTHLY")
        assertThat(captor.firstValue.recurrence?.day).isEqualTo(25)
    }

    @Test
    fun `PATCH clear recurrence stops recurring`() {
        stubBoards()
        val recurring = aTask().copy(recurrence = TaskRecurrence(RecurrenceKind.EVERY_N_DAYS, every = 45))
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(recurring)
        whenever(backlogTaskService.updateTask(eq(userId), eq(boardId), eq(taskId), any())).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clear":["recurrence"]}""")
        ).andExpect(status().isOk)

        val captor = argumentCaptor<UpdateBacklogTaskRequest>()
        verify(backlogTaskService).updateTask(eq(userId), eq(boardId), eq(taskId), captor.capture())
        assertThat(captor.firstValue.recurrence).isNull()
    }

    @Test
    fun `PATCH with a malformed recurrence is a 400 naming the rule`() {
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"recurrence":{"kind":"MONTHLY","day":40}}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("MONTHLY requires 'day' between 1 and 31")))
    }

    @Test
    fun `PATCH rejects clearing a field that is not clearable`() {
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clear":["title"]}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH rejects an unknown priority with a 400, not a 500`() {
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"priority":"urgent"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("low")))
    }

    @Test
    fun `PATCH rejects an in-app link with a 400`() {
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(aTask())

        mockMvc.perform(
            patch("$basePath/$taskId")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"app:clear-tutorial"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("http://")))
    }

    // --- Create ---

    @Test
    fun `POST with only a title falls back to the default board and its first category`() {
        stubBoards()
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
        whenever(categoryService.getCategories(userId, boardId))
            .thenReturn(listOf(BacklogTaskCategory(categoryId, boardId, "Work", CategoryColor.SUNSHINE)))
        whenever(backlogTaskService.createTask(eq(userId), eq(boardId), any())).thenReturn(aTask())

        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Renew passport"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Renew passport"))
    }

    @Test
    fun `POST onto a board with no categories is a 422 explaining why`() {
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
        whenever(categoryService.getCategories(userId, boardId)).thenReturn(emptyList())

        mockMvc.perform(
            post(basePath)
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Renew passport"}""")
        ).andExpect(status().isUnprocessableEntity)
    }

    // --- Actions ---

    @Test
    fun `POST complete delegates to markDone`() {
        stubBoards()
        whenever(backlogTaskService.markDone(userId, taskId)).thenReturn(aTask(status = TaskStatus.DONE))

        mockMvc.perform(post("$basePath/$taskId/complete").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("done"))

        verify(backlogTaskService).markDone(userId, taskId)
    }

    @Test
    fun `POST archive delegates to archive`() {
        stubBoards()
        whenever(backlogTaskService.archive(userId, taskId)).thenReturn(aTask(status = TaskStatus.ARCHIVED))

        mockMvc.perform(post("$basePath/$taskId/archive").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("archived"))
    }

    // --- Scope enforcement ---

    @Test
    fun `a read-scoped token may read`() {
        stubBoards()
        whenever(backlogTaskService.getTasksAcrossBoards(userId, TaskStatus.TODO)).thenReturn(emptyList())

        mockMvc.perform(get(basePath).with(authentication(readOnlyAuth)))
            .andExpect(status().isOk)
    }

    @Test
    fun `a read-scoped token may not create`() {
        mockMvc.perform(
            post(basePath)
                .with(authentication(readOnlyAuth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"Nope"}""")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `a read-scoped token may not complete a task`() {
        mockMvc.perform(post("$basePath/$taskId/complete").with(authentication(readOnlyAuth)))
            .andExpect(status().isForbidden)
    }
}
