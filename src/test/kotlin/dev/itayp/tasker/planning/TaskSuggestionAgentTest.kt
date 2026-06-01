package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatResponse
import dev.itayp.tasker.ai.client.Choice
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BacklogTaskTagService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskSuggestionAgentTest {

    private val aiClient: AiClient = mock()
    private val backlogTaskService: BacklogTaskService = mock()
    private val categoryService: BacklogTaskCategoryService = mock()
    private val tagService: BacklogTaskTagService = mock()
    private val objectMapper = jacksonObjectMapper()
    private val agent = TaskSuggestionAgent(
        aiClient, backlogTaskService, categoryService, tagService, objectMapper, "test-model",
    )

    private val userId = UUID.randomUUID()

    @BeforeEach
    fun stubContext() {
        whenever(categoryService.getAllForUser(userId)).thenReturn(
            listOf(BacklogTaskCategory(UUID.randomUUID(), userId, "Health", CategoryColor.PEACH)),
        )
        whenever(tagService.getAllForUser(userId)).thenReturn(emptyList())
        whenever(backlogTaskService.getTasksForUser(userId, null)).thenReturn(emptyList())
    }

    @Test
    fun `drafts a task from the model output without persisting`() {
        val categoryId = UUID.randomUUID()
        whenever(aiClient.chat(any())).thenReturn(
            chatResponse(
                """{"title":"Call the dentist","category_id":"$categoryId","priority":"medium",
                   "tags":[{"id":null,"label":"health","color_id":"rose"}]}""",
            ),
        )

        val draft = agent.suggest(userId, "book a dentist appointment")

        assertEquals("Call the dentist", draft?.title)
        assertEquals(categoryId.toString(), draft?.categoryId)
        assertEquals("rose", draft?.tags?.first()?.colorId)
        // Drafting must never write to the backlog.
        verify(backlogTaskService, never()).createTask(any(), any())
    }

    @Test
    fun `returns null when the model output is not parseable`() {
        whenever(aiClient.chat(any())).thenReturn(chatResponse("no json here"))

        assertNull(agent.suggest(userId, "whatever"))
    }

    private fun chatResponse(content: String) = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(message = ChatMessage(role = "assistant", content = content), finishReason = "stop")),
        usage = null,
    )
}
