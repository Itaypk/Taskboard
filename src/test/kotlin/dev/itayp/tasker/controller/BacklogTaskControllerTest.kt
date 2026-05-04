package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
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
import java.time.Instant
import java.util.UUID

@WebMvcTest(BacklogTaskController::class)
@Import(SecurityConfiguration::class)
class BacklogTaskControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var backlogTaskService: BacklogTaskService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val categoryId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun aTask(id: UUID = taskId, title: String = "Test Task") = BacklogTask(
        id = id,
        userId = userId,
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = TaskStatus.TODO,
        category = BacklogTaskCategory(categoryId, userId, "Work", CategoryColor.SUNSHINE),
        tags = emptySet(),
        sortKey = SortKeyGenerator.INITIAL,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null
    )

    @Test
    fun `GET tasks returns 200 with task list`() {
        whenever(backlogTaskService.getAllTasksForUser(userId)).thenReturn(listOf(aTask()))

        mockMvc.perform(get("/api/v1/tasks").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].title").value("Test Task"))
            .andExpect(jsonPath("$[0].status").value("todo"))
            .andExpect(jsonPath("$[0].categoryId").value(categoryId.toString()))
    }

    @Test
    fun `GET tasks returns 200 with empty list`() {
        whenever(backlogTaskService.getAllTasksForUser(userId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/tasks").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET tasks unauthenticated returns 401`() {
        mockMvc.perform(get("/api/v1/tasks"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST tasks returns 201 with created task`() {
        whenever(backlogTaskService.createTask(eq(userId), any())).thenReturn(aTask(title = "New Task"))

        mockMvc.perform(
            post("/api/v1/tasks")
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
    fun `PUT tasks-id returns 200 with updated task`() {
        whenever(backlogTaskService.updateTask(eq(userId), eq(taskId), any())).thenReturn(aTask(title = "Updated"))

        mockMvc.perform(
            put("/api/v1/tasks/$taskId")
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
        whenever(backlogTaskService.updateTask(any(), any(), any()))
            .thenThrow(NoSuchElementException("not found"))

        mockMvc.perform(
            put("/api/v1/tasks/$taskId")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"X","status":"todo","categoryId":"$categoryId","tags":[]}""")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE tasks-id returns 204`() {
        mockMvc.perform(delete("/api/v1/tasks/$taskId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE tasks-id returns 404 when task not found`() {
        doThrow(NoSuchElementException("not found")).whenever(backlogTaskService).deleteTask(any(), any())

        mockMvc.perform(delete("/api/v1/tasks/$taskId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PATCH tasks-id-reorder returns 200 with updated task`() {
        whenever(backlogTaskService.reorderTask(eq(userId), eq(taskId), any())).thenReturn(aTask())

        mockMvc.perform(
            patch("/api/v1/tasks/$taskId/reorder")
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
        whenever(backlogTaskService.reorderTask(any(), any(), any()))
            .thenThrow(NoSuchElementException("not found"))

        mockMvc.perform(
            patch("/api/v1/tasks/$taskId/reorder")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"afterId":null,"beforeId":null}""")
        )
            .andExpect(status().isNotFound)
    }
}
