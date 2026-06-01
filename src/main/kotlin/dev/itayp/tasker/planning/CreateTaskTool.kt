package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.tasker.ai.tool.AiTool
import dev.itayp.tasker.ai.tool.ToolKind
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.service.BacklogTaskService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Persists a backlog task the user has approved (typically after a `suggest_task` draft) and returns
 * its `task_id` so the planner can schedule it via `submit_plan`. Deliberately thin and deterministic:
 * all drafting/judgement happens in `suggest_task`; this tool only writes what the user confirmed.
 * Call it ONLY after the user has agreed to the task.
 */
@Component
class CreateTaskTool(
    private val backlogTaskService: BacklogTaskService,
    private val toolContext: PlanningToolContext,
    private val objectMapper: ObjectMapper,
) : AiTool {

    private val log = LoggerFactory.getLogger(CreateTaskTool::class.java)

    override val name: String = "create_task"

    override val description: String =
        "Persist a new backlog task the user has approved, and get back its task_id. Use the fields from " +
            "the agreed `suggest_task` draft (with any corrections the user made). Call this only after the " +
            "user has confirmed they want the task created; then use the returned task_id in `submit_plan`."

    override val kind: ToolKind = ToolKind.DATA_LOOKUP

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "Short, actionable task title."),
            "category_id" to mapOf("type" to "string", "description" to "UUID of an existing category (see the categories list)."),
            "description" to mapOf("type" to "string", "description" to "Optional longer description / notes."),
            "priority" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high"), "description" to "Optional priority."),
            "deadline" to mapOf("type" to "string", "description" to "Optional deadline, YYYY-MM-DD."),
            "estimated_minutes" to mapOf("type" to "integer", "description" to "Optional time estimate in minutes."),
            "tags" to mapOf(
                "type" to "array",
                "description" to "Optional tags. Reuse an existing tag by passing its id; or create one with a new label + color_id.",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string", "description" to "UUID of an existing tag, if reusing one."),
                        "label" to mapOf("type" to "string", "description" to "Tag label."),
                        "color_id" to mapOf("type" to "string", "description" to "Color name for a new tag (e.g. sage, sky, coral)."),
                    ),
                    "required" to listOf("label", "color_id"),
                ),
            ),
        ),
        "required" to listOf("title", "category_id"),
    )

    override fun execute(arguments: String): String {
        val args = runCatching { objectMapper.readValue(arguments, CreateTaskArgs::class.java) }
            .getOrElse {
                log.warn("create_task received invalid payload: {}", it.message)
                return """{"error":"Could not parse arguments"}"""
            }
        val userId = toolContext.requireUserId()

        val request = CreateBacklogTaskRequest(
            title = args.title,
            description = args.description,
            priority = args.priority,
            deadline = args.deadline,
            estimatedMinutes = args.estimatedMinutes,
            categoryId = args.categoryId,
            tags = args.tags.map { TagInput(id = it.id, label = it.label, colorId = it.colorId) },
        )

        return runCatching {
            val created = backlogTaskService.createTask(userId, request)
            log.debug("create_task created backlog task {}", created.id)
            objectMapper.writeValueAsString(mapOf("task_id" to created.id.toString(), "title" to created.title))
        }.getOrElse { e ->
            // Surface a structured error so the model can correct (e.g. a bad category_id or color).
            log.warn("create_task failed: {}", e.message)
            objectMapper.writeValueAsString(mapOf("error" to (e.message ?: "Could not create task")))
        }
    }

    private data class CreateTaskArgs(
        val title: String,
        @JsonProperty("category_id") val categoryId: String,
        val description: String? = null,
        val priority: String? = null,
        val deadline: String? = null,
        @JsonProperty("estimated_minutes") val estimatedMinutes: Int? = null,
        val tags: List<TagArg> = emptyList(),
    )

    private data class TagArg(
        val id: String? = null,
        val label: String,
        @JsonProperty("color_id") val colorId: String,
    )
}
