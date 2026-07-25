package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.itayp.nescioquid.openrouter.jsonSchema
import dev.itayp.nescioquid.openrouter.tool.AiTool
import dev.itayp.nescioquid.openrouter.tool.ToolKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Drafts a complete backlog task (title, category, priority, deadline, estimate, tags) from the
 * user's words via [TaskSuggestionAgent]. It does NOT persist anything — the planner is expected to
 * show the draft to the user, let them confirm or correct it, and only then call `create_task`.
 */
@Component
class SuggestTaskTool(
    private val suggestionAgent: TaskSuggestionAgent,
    private val toolContext: PlanningToolContext,
    private val objectMapper: ObjectMapper,
) : AiTool {

    private val log = LoggerFactory.getLogger(SuggestTaskTool::class.java)

    override val name: String = "suggest_task"

    override val description: String =
        "Draft a new backlog task from a free-text description when the user wants to schedule " +
            "something that isn't in the backlog yet (check `find_task` first to avoid duplicates). " +
            "Returns a proposed task with category, priority, deadline, estimate and tags. This does NOT " +
            "save anything: present the draft to the user, let them adjust it, then call `create_task`."

    override val kind: ToolKind = ToolKind.DATA_LOOKUP

    override val parameters: Map<String, Any> = jsonSchema<SuggestTaskArgs>(strict = false)

    override fun execute(arguments: String): String {
        val description = runCatching { objectMapper.readValue(arguments, SuggestTaskArgs::class.java).description }
            .getOrElse {
                log.warn("suggest_task received invalid payload: {}", it.message)
                return """{"error":"Could not parse arguments"}"""
            }
        val userId = toolContext.requireUserId()
        val draft = suggestionAgent.suggest(userId, description)
            ?: return """{"error":"Could not draft a task; ask the user for the details instead."}"""
        return objectMapper.writeValueAsString(mapOf("draft" to draft))
    }

    private data class SuggestTaskArgs(
        @JsonPropertyDescription("What the new task is about, in natural language — the user's words or your distillation of them.")
        val description: String,
    )
}
