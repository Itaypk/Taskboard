package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.itayp.nescioquid.openrouter.jsonSchema
import dev.itayp.nescioquid.openrouter.tool.AiTool
import dev.itayp.nescioquid.openrouter.tool.ToolKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Lets the planner check whether a task the user mentions already exists in the backlog, beyond the
 * bounded candidate slate it was given. Delegates to [BacklogTaskSearchAgent] (an isolated LLM call
 * over the full backlog) and hands the matches back so the model can reuse an existing `task_id`
 * instead of creating a duplicate via `suggest_task`/`create_task`.
 */
@Component
class FindTaskTool(
    private val searchAgent: BacklogTaskSearchAgent,
    private val toolContext: PlanningToolContext,
    private val objectMapper: ObjectMapper,
) : AiTool {

    private val log = LoggerFactory.getLogger(FindTaskTool::class.java)

    override val name: String = "find_task"

    override val description: String =
        "Search the user's full backlog for an existing task matching a free-text description. " +
            "Use this before creating a new task, so an item the user mentions that's already in the " +
            "backlog (but not in the candidate list) is reused rather than duplicated. Returns matching " +
            "tasks with their task_id; pick one if it's clearly the same task."

    override val kind: ToolKind = ToolKind.DATA_LOOKUP

    override val parameters: Map<String, Any> = jsonSchema<FindTaskArgs>(strict = false)

    override fun execute(arguments: String): String {
        val query = runCatching { objectMapper.readValue(arguments, FindTaskArgs::class.java).query }
            .getOrElse {
                log.warn("find_task received invalid payload: {}", it.message)
                return """{"error":"Could not parse arguments"}"""
            }
        val userId = toolContext.requireUserId()
        val matches = searchAgent.search(userId, query)
        return objectMapper.writeValueAsString(mapOf("matches" to matches))
    }

    private data class FindTaskArgs(
        @JsonPropertyDescription("What the user is looking for, in natural language (e.g. 'the taxes thing' or 'call the dentist').")
        val query: String,
    )
}
