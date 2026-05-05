package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.tool.AiTool
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
                            "description" to "UUID of the backlog task as shown in the candidate list. Omit only if the user agreed to a task that wasn't in the candidate list (e.g. ad-hoc).",
                        ),
                        "title" to mapOf(
                            "type" to "string",
                            "description" to "Short title for the task (echo from the backlog or a fresh ad-hoc title).",
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
                    "required" to listOf("title", "slots"),
                ),
            ),
            "summary" to mapOf(
                "type" to "string",
                "description" to "Short human-readable recap to store as this session's summary (used as memory for next week).",
            ),
        ),
        "required" to listOf("tasks", "summary"),
    )

    override fun execute(arguments: String): String {
        val plan = try {
            objectMapper.readValue(arguments, AgreedPlan::class.java)
        } catch (e: Exception) {
            log.warn("submit_plan received invalid payload: ${e.message}")
            return """{"ok": false, "error": "Could not parse plan: ${e.message}"}"""
        }
        inbox.record(plan)
        return """{"ok": true, "tasks_recorded": ${plan.tasks.size}}"""
    }
}
