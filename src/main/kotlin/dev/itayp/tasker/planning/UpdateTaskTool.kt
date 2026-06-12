package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.tool.AiTool
import dev.itayp.tasker.ai.tool.ToolKind
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.model.request.UpdateBacklogTaskRequest
import dev.itayp.tasker.service.BacklogTaskService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Modifies an existing backlog task the user has approved changing — either detail edits
 * (title, description, tags, …) or a state change (todo / done / archived). Counterpart to
 * [CreateTaskTool]: thin and deterministic, it only writes what the user confirmed.
 *
 * Partial-update semantics: the underlying [BacklogTaskService.updateTask] is a full replacement,
 * but the planner naturally sends only the fields it wants to change ("mark done", "push the
 * deadline"). So we load the current task, seed a complete request from it, and overlay only the
 * keys the model actually provided. Presence is detected from the parsed JSON map (an absent key
 * keeps the current value; a present `null`/empty string clears a nullable field) — a distinction
 * Jackson cannot make when binding to a data class with nullable defaults.
 *
 * There is deliberately no hard-delete tool: archiving (`status: "archived"`) is the reversible
 * equivalent, and an LLM acting on fuzzy intent must never be able to permanently destroy data.
 */
@Component
class UpdateTaskTool(
    private val backlogTaskService: BacklogTaskService,
    private val toolContext: PlanningToolContext,
    private val objectMapper: ObjectMapper,
) : AiTool {

    private val log = LoggerFactory.getLogger(UpdateTaskTool::class.java)

    override val name: String = "update_task"

    override val description: String = """
        Modify an existing backlog task the user has approved changing. Provide its task_id plus ONLY the
        fields you want to change; omitted fields are left untouched. Set status to 'done' to mark it
        complete or 'archived' to remove it from active lists (archive is the reversible way to delete —
        there is no hard delete). Call this only after the user confirms the change.
    """.trimIndent()

    override val kind: ToolKind = ToolKind.DATA_LOOKUP

    override val parameters: Map<String, Any> = mapOf(
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

    @Suppress("UNCHECKED_CAST")
    override fun execute(arguments: String): String {
        val root: Map<String, Any?> = runCatching {
            objectMapper.readValue(arguments, Map::class.java) as Map<String, Any?>
        }.getOrElse {
            log.warn("update_task received invalid payload: {}", it.message)
            return errorJson("Could not parse arguments")
        }

        val taskIdText = (root["task_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: return errorJson("task_id is required")
        val taskId = runCatching { UUID.fromString(taskIdText) }
            .getOrElse { return errorJson("task_id is not a valid id") }

        val userId = toolContext.requireUserId()

        // The task may live on any of the user's boards; findTask carries its boardId so the update
        // is applied to the right one.
        val current = backlogTaskService.findTask(userId, taskId)
            ?: return errorJson("Task $taskId not found")

        // Seed a complete request from the current task, overlaying only the keys the model sent.
        val request = UpdateBacklogTaskRequest(
            title = (root["title"] as? String)?.takeIf { it.isNotBlank() } ?: current.title,
            description = if (root.containsKey("description")) root["description"] as? String else current.description,
            url = if (root.containsKey("url")) root["url"] as? String else current.url,
            priority = if (root.containsKey("priority")) root["priority"] as? String else current.priority?.name?.lowercase(),
            deadline = if (root.containsKey("deadline")) root["deadline"] as? String else current.deadline?.toString(),
            estimatedMinutes = if (root.containsKey("estimated_minutes")) (root["estimated_minutes"] as? Number)?.toInt() else current.estimatedMinutes,
            status = (root["status"] as? String)?.takeIf { it.isNotBlank() } ?: current.status.name.lowercase(),
            categoryId = (root["category_id"] as? String)?.takeIf { it.isNotBlank() } ?: current.category.id.toString(),
            tags = if (root.containsKey("tags")) parseTags(root["tags"]) else current.tags.map { it.toTagInput() },
            relevantFrom = if (root.containsKey("relevant_from")) root["relevant_from"] as? String else current.relevantFrom?.toString(),
        )

        return runCatching {
            val updated = backlogTaskService.updateTask(userId, current.boardId, taskId, request)
            log.debug("update_task updated backlog task {}", updated.id)
            objectMapper.writeValueAsString(
                mapOf(
                    "task_id" to updated.id.toString(),
                    "title" to updated.title,
                    "status" to updated.status.name.lowercase(),
                ),
            )
        }.getOrElse { e ->
            // Surface a structured error so the model can correct (e.g. a bad category_id).
            log.warn("update_task failed: {}", e.message)
            errorJson(e.message ?: "Could not update task")
        }
    }

    private fun parseTags(raw: Any?): List<TagInput> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            // Each element is a complete tag (no partial semantics), so bind it to a typed TagArg —
            // shared with create_task — rather than reading the map by hand. Skip anything malformed
            // (e.g. a missing label) so one bad tag doesn't fail the whole update.
            val arg = runCatching { objectMapper.convertValue(item, TagArg::class.java) }.getOrNull()
            val label = arg?.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TagInput(id = arg.id, label = label, colorId = TagColorOptions.resolve(arg.colorId))
        }
    }

    private fun errorJson(message: String): String = objectMapper.writeValueAsString(mapOf("error" to message))

    private fun BacklogTaskTag.toTagInput(): TagInput =
        TagInput(id = id.toString(), label = label, colorId = colorId.name.lowercase())
}
