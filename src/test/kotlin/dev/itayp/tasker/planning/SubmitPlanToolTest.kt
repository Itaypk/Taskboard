package dev.itayp.tasker.planning

import dev.itayp.tasker.planning.dto.AgreedPlan
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubmitPlanToolTest {

    private val objectMapper = jacksonObjectMapper()

    private val inbox = PlanSubmissionInbox()
    private val tool = SubmitPlanTool(inbox, objectMapper)

    @Test
    fun `parses a valid plan and records into the inbox`() {
        inbox.begin()
        val args = """
        {
          "tasks": [
            {
              "task_id": "11111111-1111-1111-1111-111111111111",
              "title": "Write spec",
              "slots": [
                {"start_iso": "2026-05-04T09:00:00+02:00", "end_iso": "2026-05-04T11:00:00+02:00", "label": "Mon morning"}
              ]
            }
          ],
          "summary": "Agreed on writing the spec Monday morning."
        }
        """.trimIndent()

        val result = tool.execute(args)

        assertTrue(result.contains("\"ok\": true"))
        val drained = inbox.drain()
        assertEquals(1, drained.size)
        val plan: AgreedPlan = drained.single()
        assertEquals(1, plan.tasks.size)
        assertEquals("Write spec", plan.tasks.single().title)
        assertEquals("Agreed on writing the spec Monday morning.", plan.summary)
    }

    @Test
    fun `invalid payload returns an error string and does not record`() {
        inbox.begin()
        val result = tool.execute("not-json")
        assertTrue(result.contains("\"ok\": false"))
        assertEquals(0, inbox.drain().size)
    }

    @Test
    fun `rejects a task missing task_id`() {
        inbox.begin()
        val args = """
        {
          "tasks": [
            {
              "title": "Write spec",
              "slots": [
                {"start_iso": "2026-05-04T09:00:00+02:00", "end_iso": "2026-05-04T11:00:00+02:00"}
              ]
            }
          ],
          "summary": "Missing the id."
        }
        """.trimIndent()

        val result = tool.execute(args)

        assertTrue(result.contains("\"ok\": false"))
        assertEquals(0, inbox.drain().size)
    }

    @Test
    fun `tool advertises required schema fields`() {
        @Suppress("UNCHECKED_CAST")
        val required = tool.parameters["required"] as List<String>
        assertTrue("tasks" in required)
        assertTrue("summary" in required)

        @Suppress("UNCHECKED_CAST")
        val properties = tool.parameters["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val tasks = properties["tasks"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val items = tasks["items"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val itemRequired = items["required"] as List<String>
        assertTrue("task_id" in itemRequired)
    }
}
