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

    @Test
    fun `say schema matches the original hand-written shape`() {
        val tool = SayTool()

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "The message body shown to the user."),
                "suggested_replies" to mapOf(
                    "type" to "array",
                    "description" to "Optional short reply suggestions the channel can render " +
                        "as autocompletions. Ignored on channels that don't support them.",
                    "items" to mapOf("type" to "string"),
                ),
            ),
            "required" to listOf("text"),
        )
        assertEquals(expected, tool.parameters)
    }

    @Test
    fun `ask_choice schema matches the original hand-written shape`() {
        val tool = AskChoiceTool()

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "prompt" to mapOf("type" to "string", "description" to "The question shown to the user."),
                "options" to mapOf(
                    "type" to "array",
                    "description" to "The choices the user can pick from.",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "string", "description" to "Stable identifier for this option, e.g. 'slot_a' or 'discuss'."),
                            "label" to mapOf("type" to "string", "description" to "Short label shown to the user."),
                        ),
                        "required" to listOf("id", "label"),
                    ),
                ),
            ),
            "required" to listOf("prompt", "options"),
        )
        assertEquals(expected, tool.parameters)
    }

    @Test
    fun `suggest_task schema matches the original hand-written shape`() {
        val tool = SuggestTaskTool(mock(), mock(), jacksonObjectMapper())

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "description" to mapOf(
                    "type" to "string",
                    "description" to "What the new task is about, in natural language — the user's words or your distillation of them.",
                ),
            ),
            "required" to listOf("description"),
        )
        assertEquals(expected, tool.parameters)
    }

    @Test
    fun `update_task schema matches the original hand-written shape`() {
        val tool = UpdateTaskTool(mock(), mock(), jacksonObjectMapper())

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task_id" to mapOf("type" to "string", "description" to "UUID of the existing task to update."),
                "title" to mapOf("type" to "string", "description" to "New title. Omit to keep the current title."),
                "category_id" to mapOf("type" to "string", "description" to "UUID of a category to move the task to. Omit to keep current."),
                "description" to mapOf("type" to "string", "description" to "New description / notes. Omit to keep; pass an empty string to clear."),
                "url" to mapOf("type" to "string", "description" to "New URL. Omit to keep; empty string to clear."),
                "priority" to mapOf("type" to "string", "enum" to TaskPriority.allowedValues.toList(), "description" to "New priority. Omit to keep current."),
                "deadline" to mapOf("type" to "string", "description" to "New deadline, YYYY-MM-DD. Omit to keep; empty string to clear."),
                "estimated_minutes" to mapOf("type" to "integer", "description" to "New time estimate in minutes. Omit to keep current."),
                "status" to mapOf(
                    "type" to "string",
                    "enum" to listOf("todo", "done", "archived"),
                    "description" to "Set 'done' to complete or 'archived' to remove from active lists. Omit to keep current.",
                ),
                "relevant_from" to mapOf("type" to "string", "description" to "Date the task becomes relevant, YYYY-MM-DD. Omit to keep; empty string to clear."),
                "tags" to mapOf(
                    "type" to "array",
                    "description" to "REPLACES the entire tag set when present. Omit to keep the current tags; pass an empty " +
                        "array to remove all tags. Reuse an existing tag by passing its id, or create one with a new label " +
                        "(and optionally a color_id).",
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
            "required" to listOf("task_id"),
        )
        assertEquals(expected, tool.parameters)
    }

    @Test
    fun `submit_plan schema matches the original hand-written shape`() {
        val tool = SubmitPlanTool(mock(), jacksonObjectMapper())

        val expected = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "tasks" to mapOf(
                    "type" to "array",
                    "description" to "Tasks the user agreed to tackle this week, with proposed time slots.",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "task_id" to mapOf(
                                "type" to "string",
                                "description" to "UUID of the backlog task this slot is for. REQUIRED for every task. " +
                                    "If the task isn't in the candidate list, call `find_task` to locate it, " +
                                    "or `suggest_task` + `create_task` to make a new one, BEFORE submitting.",
                            ),
                            "title" to mapOf("type" to "string", "description" to "Short title for the task (echo from the backlog)."),
                            "slots" to mapOf(
                                "type" to "array",
                                "description" to "One or more agreed time slots for this task.",
                                "items" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "start_iso" to mapOf("type" to "string", "description" to "Start time, ISO-8601 with offset."),
                                        "end_iso" to mapOf("type" to "string", "description" to "End time, ISO-8601 with offset."),
                                        "label" to mapOf("type" to "string", "description" to "Optional human label, e.g. 'Tue morning deep work'."),
                                    ),
                                    "required" to listOf("start_iso", "end_iso"),
                                ),
                            ),
                            "notes" to mapOf("type" to "string", "description" to "Optional short note, e.g. split decisions."),
                        ),
                        "required" to listOf("task_id", "title", "slots"),
                    ),
                ),
                "summary" to mapOf(
                    "type" to "string",
                    "description" to """
                        A self-contained memory note for this week's plan, carried into future sessions.
                        Recap what was scheduled, plus any context worth remembering next time (preferences,
                        deferrals, what the user is juggling). Make it stand on its own — not a recap of the
                        last turn. When revising an existing plan, return the COMPLETE updated note: merge the
                        prior summary with what changed this session rather than replacing it with just the change.
                    """.trimIndent(),
                ),
                "message" to mapOf(
                    "type" to "string",
                    "description" to "The closing message shown to the user confirming the finalized plan. " +
                        "Write it in the user's language and warm tone — this is the last thing they see, " +
                        "so recap what was scheduled. Always end with one short, first-person sentence stating " +
                        "which notification channel(s) apply to this plan (calendar invite email, a Telegram " +
                        "reminder, both, or plainly neither) — see the system prompt's delivery-methods context " +
                        "for the facts, and say so honestly even when the answer is neither. Do NOT also send a " +
                        "separate `say`; this field replaces it.",
                ),
                "context_suggestion" to mapOf(
                    "type" to "string",
                    "description" to """
                        OPTIONAL. A single durable fact about the user — a stable preference, routine, or
                        constraint — that you learned this session and that is worth remembering for future
                        weeks. After finalizing, the user is asked to accept or reject adding it to their
                        personal context. Include this ONLY when you genuinely learned something new and
                        lasting that is NOT already covered by the user's context block above; omit the field
                        otherwise (most sessions won't need it). Phrase it as one concise first-person context
                        line in the user's language, e.g. "I prefer not to schedule work on Tuesday evenings."
                        One fact only — not a recap of the week, not a task, not the plan summary.
                    """.trimIndent(),
                ),
            ),
            "required" to listOf("tasks", "summary", "message"),
        )
        assertEquals(expected, tool.parameters)
    }
}
