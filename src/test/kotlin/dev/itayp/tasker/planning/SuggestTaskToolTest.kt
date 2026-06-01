package dev.itayp.tasker.planning

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertTrue

class SuggestTaskToolTest {

    private val suggestionAgent: TaskSuggestionAgent = mock()
    private val context = PlanningToolContext()
    private val objectMapper = jacksonObjectMapper()
    private val tool = SuggestTaskTool(suggestionAgent, context, objectMapper)

    @Test
    fun `returns the draft and persists nothing`() {
        val userId = UUID.randomUUID()
        context.begin(userId, ZoneOffset.UTC)
        val draft = TaskDraft(
            title = "Call the dentist",
            categoryId = UUID.randomUUID().toString(),
            priority = "medium",
            tags = listOf(TagDraft(label = "health", colorId = "rose")),
        )
        whenever(suggestionAgent.suggest(userId, "book a dentist appointment")).thenReturn(draft)

        val result = tool.execute("""{"description":"book a dentist appointment"}""")
        context.clear()

        assertTrue(result.contains("\"draft\""))
        assertTrue(result.contains("Call the dentist"))
    }

    @Test
    fun `returns an error when the agent cannot draft`() {
        val userId = UUID.randomUUID()
        context.begin(userId, ZoneOffset.UTC)
        whenever(suggestionAgent.suggest(userId, "???")).thenReturn(null)

        val result = tool.execute("""{"description":"???"}""")
        context.clear()

        assertTrue(result.contains("\"error\""))
    }
}
