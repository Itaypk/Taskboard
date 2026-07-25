package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.itayp.nescioquid.openrouter.jsonSchema
import dev.itayp.nescioquid.openrouter.tool.AiTool
import dev.itayp.nescioquid.openrouter.tool.ToolKind
import dev.itayp.tasker.planning.dto.AgreedPlan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Tool the assistant calls to submit the final agreed weekly plan. The tool itself does
 * no I/O; it parses the payload and hands it to [PlanSubmissionInbox] for the orchestrator
 * to pick up after the AI turn completes.
 */
@Component
class SubmitPlanTool(
    private val inbox: PlanSubmissionInbox,
    private val objectMapper: ObjectMapper,
) : AiTool {

    private val log = LoggerFactory.getLogger(SubmitPlanTool::class.java)

    override val name: String = "submit_plan"

    override val description: String =
        "Submit the agreed weekly plan once the user has confirmed it. Call exactly once per session."

    override val kind: ToolKind = ToolKind.ONE_WAY_OUTPUT

    override val parameters: Map<String, Any> = jsonSchema<SubmitPlanArgs>(strict = false)

    override fun execute(arguments: String): String {
        val plan = try {
            objectMapper.readValue(arguments, AgreedPlan::class.java)
        } catch (e: Exception) {
            log.warn("submit_plan received invalid payload: ${e.message}")
            return """{"ok": false, "error": "Could not parse plan: ${e.message}"}"""
        }
        inbox.record(plan)
        log.debug(
            "submit_plan recorded plan: tasks={} hasMessage={} hasContextSuggestion={}",
            plan.tasks.size,
            !plan.message.isNullOrBlank(),
            !plan.contextSuggestion.isNullOrBlank(),
        )
        return """{"ok": true, "tasks_recorded": ${plan.tasks.size}}"""
    }

    /**
     * Schema shape only — `execute` binds the payload to [AgreedPlan]. This mirror exists because
     * [AgreedPlan] can't generate the schema directly: its `task_id` is a `UUID` (unsupported by the
     * schema generator) and its `message` is nullable, whereas the tool contract requires `message`
     * and presents `task_id` as a string. Keep the two in sync until the generator learns `UUID`.
     */
    private data class SubmitPlanArgs(
        @JsonPropertyDescription("Tasks the user agreed to tackle this week, with proposed time slots.")
        val tasks: List<PlanTaskArg>,
        @JsonPropertyDescription(
            "A self-contained memory note for this week's plan, carried into future sessions.\n" +
                "Recap what was scheduled, plus any context worth remembering next time (preferences,\n" +
                "deferrals, what the user is juggling). Make it stand on its own — not a recap of the\n" +
                "last turn. When revising an existing plan, return the COMPLETE updated note: merge the\n" +
                "prior summary with what changed this session rather than replacing it with just the change.",
        )
        val summary: String,
        @JsonPropertyDescription(
            "The closing message shown to the user confirming the finalized plan. " +
                "Write it in the user's language and warm tone — this is the last thing they see, " +
                "so recap what was scheduled. Do NOT also send a separate `say`; this field replaces it.",
        )
        val message: String,
        @JsonProperty("context_suggestion")
        @JsonPropertyDescription(
            "OPTIONAL. A single durable fact about the user — a stable preference, routine, or\n" +
                "constraint — that you learned this session and that is worth remembering for future\n" +
                "weeks. After finalizing, the user is asked to accept or reject adding it to their\n" +
                "personal context. Include this ONLY when you genuinely learned something new and\n" +
                "lasting that is NOT already covered by the user's context block above; omit the field\n" +
                "otherwise (most sessions won't need it). Phrase it as one concise first-person context\n" +
                "line in the user's language, e.g. \"I prefer not to schedule work on Tuesday evenings.\"\n" +
                "One fact only — not a recap of the week, not a task, not the plan summary.",
        )
        val contextSuggestion: String? = null,
    )

    private data class PlanTaskArg(
        @JsonProperty("task_id")
        @JsonPropertyDescription(
            "UUID of the backlog task this slot is for. REQUIRED for every task. " +
                "If the task isn't in the candidate list, call `find_task` to locate it, " +
                "or `suggest_task` + `create_task` to make a new one, BEFORE submitting.",
        )
        val taskId: String,
        @JsonPropertyDescription("Short title for the task (echo from the backlog).")
        val title: String,
        @JsonPropertyDescription("One or more agreed time slots for this task.")
        val slots: List<PlanSlotArg>,
        @JsonPropertyDescription("Optional short note, e.g. split decisions.")
        val notes: String? = null,
    )

    private data class PlanSlotArg(
        @JsonProperty("start_iso")
        @JsonPropertyDescription("Start time, ISO-8601 with offset.")
        val startIso: String,
        @JsonProperty("end_iso")
        @JsonPropertyDescription("End time, ISO-8601 with offset.")
        val endIso: String,
        @JsonPropertyDescription("Optional human label, e.g. 'Tue morning deep work'.")
        val label: String? = null,
    )
}
