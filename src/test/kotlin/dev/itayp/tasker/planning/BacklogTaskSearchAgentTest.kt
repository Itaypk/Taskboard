package dev.itayp.tasker.planning

import dev.itayp.nescioquid.openrouter.AiClient
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatResponse
import dev.itayp.nescioquid.openrouter.Choice
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.service.BacklogTaskService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    private val meterRegistry = SimpleMeterRegistry()
    private val agent = BacklogTaskSearchAgent(
        aiClient, backlogTaskService, PromptTemplateLoader(), objectMapper, meterRegistry, "test-model",
    )

    @Test
    fun `returns empty and skips the model when the backlog is empty`() {
        val userId = UUID.randomUUID()
        whenever(backlogTaskService.getTasksAcrossBoards(userId, null)).thenReturn(emptyList())

        assertTrue(agent.search(userId, "anything").isEmpty())
        verifyNoInteractions(aiClient)
    }

    @Test
    fun `parses matches from the sub-agent output`() {
        val userId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        whenever(backlogTaskService.getTasksAcrossBoards(userId, null))
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
        whenever(backlogTaskService.getTasksAcrossBoards(userId, null))
            .thenReturn(listOf(backlogTask(UUID.randomUUID(), "Do taxes")))
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("sorry, no idea"))

        assertTrue(agent.search(userId, "taxes").isEmpty())
        assertEquals(
            1.0,
            meterRegistry.counter(
                "tasker.ai.parse_failures", "conversation_type", "task_search", "reason", "no_json_found",
            ).count(),
        )
    }

    private fun chatResponse(content: String) = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(message = ChatMessage(role = "assistant", content = content), finishReason = "stop")),
        usage = null,
    )

    private fun backlogTask(id: UUID, title: String) = BacklogTask(
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
