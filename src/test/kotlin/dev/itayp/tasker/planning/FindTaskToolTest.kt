package dev.itayp.tasker.planning

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertTrue

class FindTaskToolTest {

    private val searchAgent: BacklogTaskSearchAgent = mock()
    private val context = PlanningToolContext()
    private val objectMapper = jacksonObjectMapper()
    private val tool = FindTaskTool(searchAgent, context, objectMapper)

    @Test
    fun `returns matches from the search agent for the active user`() {
        val userId = UUID.randomUUID()
        context.begin(userId, ZoneOffset.UTC)
        val match = TaskMatch(taskId = UUID.randomUUID().toString(), title = "Do taxes", confidence = "high")
        whenever(searchAgent.search(userId, "taxes")).thenReturn(listOf(match))

        val result = tool.execute("""{"query":"taxes"}""")
        context.clear()

        assertTrue(result.contains("\"matches\""))
        assertTrue(result.contains("Do taxes"))
    }

    @Test
    fun `invalid payload returns an error`() {
        context.begin(UUID.randomUUID(), ZoneOffset.UTC)
        val result = tool.execute("not-json")
        context.clear()

        assertTrue(result.contains("\"error\""))
    }
}
