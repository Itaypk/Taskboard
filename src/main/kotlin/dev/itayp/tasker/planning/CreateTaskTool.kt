package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.itayp.nescioquid.openrouter.jsonSchema
import dev.itayp.nescioquid.openrouter.tool.AiTool
import dev.itayp.nescioquid.openrouter.tool.ToolKind
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Persists a backlog task the user has approved (typically after a `suggest_task` draft) and returns
 * its `task_id` so the planner can schedule it via `submit_plan`. Deliberately thin and deterministic:
 * all drafting/judgement happens in `suggest_task`; this tool only writes what the user confirmed.
 * Call it ONLY after the user has agreed to the task.
 */
@Component
class CreateTaskTool(
    private val backlogTaskService: BacklogTaskService,
    private val boardMembershipService: BoardMembershipService,
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

    override val parameters: Map<String, Any> = jsonSchema<CreateTaskArgs>(strict = false) {
        // Runtime enum lists the generator can't derive from the String-typed DTO fields.
        property("priority").enum(TaskPriority.allowedValues)
        property("tags").items().property("color_id").enum(TagColorOptions.ALLOWED)
        // `tags` is a non-null List with an empty-list default (i.e. optional), which the generator
        // would otherwise mark required; pin the required set to match the original schema.
        put("required", listOf("title", "category_id"))
    }

    override fun execute(arguments: String): String {
        val args = runCatching { objectMapper.readValue(arguments, CreateTaskArgs::class.java) }
            .getOrElse {
                log.warn("create_task received invalid payload: {}", it.message)
                return """{"error":"Could not parse arguments"}"""
            }
        val userId = toolContext.requireUserId()

        // The model may target a specific board; otherwise new tasks land on the user's default board.
        // A malformed or non-member board id surfaces as a structured error (createTask asserts membership).
        val boardId = args.boardId?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrElse {
                return """{"error":"board_id is not a valid id"}"""
            }
        } ?: boardMembershipService.resolveDefaultBoard(userId)

        val request = TaskDraft(
            title = args.title,
            description = args.description,
            categoryId = args.categoryId,
            priority = args.priority,
            deadline = args.deadline,
            estimatedMinutes = args.estimatedMinutes,
            tags = args.tags,
        ).toCreateBacklogTaskRequest()

        return runCatching {
            val created = backlogTaskService.createTask(userId, boardId, request)
            log.debug("create_task created backlog task {}", created.id)
            objectMapper.writeValueAsString(mapOf("task_id" to created.id.toString(), "title" to created.title))
        }.getOrElse { e ->
            // Surface a structured error so the model can correct (e.g. a bad category_id or color).
            log.warn("create_task failed: {}", e.message)
            objectMapper.writeValueAsString(mapOf("error" to (e.message ?: "Could not create task")))
        }
    }

    private data class CreateTaskArgs(
        @JsonPropertyDescription("Short, actionable task title.")
        val title: String,
        @JsonProperty("category_id")
        @JsonPropertyDescription("UUID of an existing category (see the categories list). It must belong to the target board.")
        val categoryId: String,
        @JsonProperty("board_id")
        @JsonPropertyDescription("Optional UUID of the board to add the task to (from the categories list headers). Omit to use your default board.")
        val boardId: String? = null,
        @JsonPropertyDescription("Optional longer description / notes.")
        val description: String? = null,
        @JsonPropertyDescription("Optional priority.")
        val priority: String? = null,
        @JsonPropertyDescription("Optional deadline, YYYY-MM-DD.")
        val deadline: String? = null,
        @JsonProperty("estimated_minutes")
        @JsonPropertyDescription("Optional time estimate in minutes.")
        val estimatedMinutes: Int? = null,
        @JsonPropertyDescription("Optional tags. Reuse an existing tag by passing its id; or create one with a new label (and optionally a color_id).")
        val tags: List<TagArg> = emptyList(),
    )
}
