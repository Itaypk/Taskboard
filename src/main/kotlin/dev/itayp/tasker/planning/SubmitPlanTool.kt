package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.tool.AiTool
import dev.itayp.tasker.ai.tool.ToolKind
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

    override val parameters: Map<String, Any> = mapOf(
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
                        "title" to mapOf(
                            "type" to "string",
                            "description" to "Short title for the task (echo from the backlog).",
                        ),
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
                "description" to "A self-contained memory note for this week's plan, carried into future sessions. " +
                    "Recap what was scheduled, plus any context worth remembering next time (preferences, " +
                    "deferrals, what the user is juggling). Make it stand on its own — not a recap of the last " +
                    "turn. When revising an existing plan, return the COMPLETE updated note: merge the prior " +
                    "summary with what changed this session rather than replacing it with just the change.",
            ),
            "message" to mapOf(
                "type" to "string",
                "description" to "The closing message shown to the user confirming the finalized plan. " +
                    "Write it in the user's language and warm tone — this is the last thing they see, " +
                    "so recap what was scheduled. Do NOT also send a separate `say`; this field replaces it.",
            ),
        ),
        "required" to listOf("tasks", "summary", "message"),
    )

    override fun execute(arguments: String): String {
        val plan = try {
            objectMapper.readValue(arguments, AgreedPlan::class.java)
        } catch (e: Exception) {
            log.warn("submit_plan received invalid payload: ${e.message}")
            return """{"ok": false, "error": "Could not parse plan: ${e.message}"}"""
        }
        inbox.record(plan)
        log.debug(
            "submit_plan recorded plan: tasks={} hasMessage={}",
            plan.tasks.size,
            !plan.message.isNullOrBlank(),
        )
        return """{"ok": true, "tasks_recorded": ${plan.tasks.size}}"""
    }
}
