package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatResponse
import dev.itayp.tasker.ai.client.Choice
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.service.BacklogTaskService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacklogTaskSearchAgentTest {

    private val aiClient: AiClient = mock()
    private val backlogTaskService: BacklogTaskService = mock()
    private val objectMapper = jacksonObjectMapper()
    private val agent = BacklogTaskSearchAgent(
        aiClient, backlogTaskService, PromptTemplateLoader(), objectMapper, "test-model",
    )

    @Test
    fun `returns empty and skips the model when the backlog is empty`() {
        val userId = UUID.randomUUID()
        whenever(backlogTaskService.getTasksForUser(userId, null)).thenReturn(emptyList())

        assertTrue(agent.search(userId, "anything").isEmpty())
        verifyNoInteractions(aiClient)
    }

    @Test
    fun `parses matches from the sub-agent output`() {
        val userId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        whenever(backlogTaskService.getTasksForUser(userId, null))
            .thenReturn(listOf(backlogTask(taskId, "Do taxes")))
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse("""{"matches":[{"task_id":"$taskId","title":"Do taxes","confidence":"high"}]}"""),
        )

        val matches = agent.search(userId, "taxes")

        assertEquals(1, matches.size)
        assertEquals(taskId.toString(), matches.first().taskId)
        assertEquals("high", matches.first().confidence)
    }

    @Test
    fun `tolerates non-JSON output and returns no matches`() {
        val userId = UUID.randomUUID()
        whenever(backlogTaskService.getTasksForUser(userId, null))
            .thenReturn(listOf(backlogTask(UUID.randomUUID(), "Do taxes")))
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("sorry, no idea"))

        assertTrue(agent.search(userId, "taxes").isEmpty())
    }

    private fun chatResponse(content: String) = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(message = ChatMessage(role = "assistant", content = content), finishReason = "stop")),
        usage = null,
    )

    private fun backlogTask(id: UUID, title: String) = BacklogTask(
        id = id,
        userId = UUID.randomUUID(),
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = TaskStatus.TODO,
        category = BacklogTaskCategory(UUID.randomUUID(), UUID.randomUUID(), "Work", CategoryColor.SUNSHINE),
        tags = emptySet(),
        sortKey = "a",
        createdAt = Instant.now(),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
    )
}
