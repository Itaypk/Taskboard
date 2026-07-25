package dev.itayp.tasker.planning

import dev.itayp.tasker.model.TaskPriority
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals

/**
 * Locks the DTO-generated tool schemas (`jsonSchema<…>()`) to the exact shape the tools previously
 * hand-wrote. `Map.equals` ignores key order (cosmetic) but compares `enum`/`required` lists by
 * order, so this asserts the schema the model sees is unchanged by the migration.
 */
class ToolParameterSchemaTest {

    @Test
    fun `find_task schema matches the original hand-written shape`() {
        val tool = FindTaskTool(mock(), mock(), jacksonObjectMapper())

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "What the user is looking for, in natural language (e.g. 'the taxes thing' or 'call the dentist').",
                ),
            ),
            "required" to listOf("query"),
        )
        assertEquals(expected, tool.parameters)
    }

    @Test
    fun `create_task schema matches the original hand-written shape`() {
        val tool = CreateTaskTool(mock(), mock(), mock(), jacksonObjectMapper())

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "description" to "Short, actionable task title."),
                "category_id" to mapOf("type" to "string", "description" to "UUID of an existing category (see the categories list). It must belong to the target board."),
                "board_id" to mapOf("type" to "string", "description" to "Optional UUID of the board to add the task to (from the categories list headers). Omit to use your default board."),
                "description" to mapOf("type" to "string", "description" to "Optional longer description / notes."),
                "priority" to mapOf("type" to "string", "enum" to TaskPriority.allowedValues.toList(), "description" to "Optional priority."),
                "deadline" to mapOf("type" to "string", "description" to "Optional deadline, YYYY-MM-DD."),
                "estimated_minutes" to mapOf("type" to "integer", "description" to "Optional time estimate in minutes."),
                "tags" to mapOf(
                    "type" to "array",
                    "description" to "Optional tags. Reuse an existing tag by passing its id; or create one with a new label (and optionally a color_id).",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "string", "description" to "UUID of an existing tag, if reusing one."),
                            "label" to mapOf("type" to "string", "description" to "Tag label."),
                            "color_id" to mapOf(
                                "type" to "string",
                                "enum" to TagColorOptions.ALLOWED,
                                "description" to "Color for a new tag. One of the allowed values; a color is assigned if omitted.",
                            ),
                        ),
                        "required" to listOf("label"),
                    ),
                ),
            ),
            "required" to listOf("title", "category_id"),
        )
        assertEquals(expected, tool.parameters)
    }
}
