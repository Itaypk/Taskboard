package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BacklogTaskTagService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Sub-agent backing the `suggest_task` tool. Drafts a complete, well-formed backlog task from the
 * user's free-text description plus style context — the user's categories, tags, and a sample of
 * their existing tasks — so a brand-new task matches how the user normally writes them. This call
 * only drafts; persistence happens later via `create_task`, after the user approves the draft.
 */
@Service
class TaskSuggestionAgent(
    private val aiClient: AiClient,
    private val backlogTaskService: BacklogTaskService,
    private val categoryService: BacklogTaskCategoryService,
    private val tagService: BacklogTaskTagService,
    private val objectMapper: ObjectMapper,
    @Value("\${tasker.ai.task-assistant-model}")
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(TaskSuggestionAgent::class.java)

    fun suggest(userId: UUID, description: String): TaskDraft? {
        val categories = categoryService.getAllForUser(userId)
        val tags = tagService.getAllForUser(userId)
        val sample = backlogTaskService.getTasksForUser(userId, null).take(SAMPLE_SIZE)

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = buildUserMessage(description, categories, tags, sample)),
            ),
            temperature = 0.3,
            maxTokens = 800,
        )

        val raw = aiClient.chat(request).choices.firstOrNull()?.message?.content.orEmpty()
        val draft = parse(raw)
        log.debug("suggest_task drafted a task (categories={}, tags={}, sample={})", categories.size, tags.size, sample.size)
        return draft
    }

    private fun buildUserMessage(
        description: String,
        categories: List<dev.itayp.tasker.model.BacklogTaskCategory>,
        tags: List<dev.itayp.tasker.model.BacklogTaskTag>,
        sample: List<BacklogTask>,
    ): String = buildString {
        append("Draft a backlog task for this request: ").append(description).append("\n\n")

        append("Available categories (use one category_id exactly):\n")
        if (categories.isEmpty()) append("(none)\n")
        categories.forEach { append("- [").append(it.id).append("] ").append(it.label).append("\n") }

        append("\nExisting tags (reuse by id where they fit; otherwise propose a new tag with a color_id from ")
        append(TagColor.entries.joinToString(", ") { it.name.lowercase() }).append("):\n")
        if (tags.isEmpty()) append("(none yet)\n")
        tags.forEach {
            append("- [").append(it.id).append("] ").append(it.label)
                .append(" (").append(it.colorId.name.lowercase()).append(")\n")
        }

        if (sample.isNotEmpty()) {
            append("\nA sample of the user's existing tasks (for style — match their tone and length):\n")
            sample.forEach { task ->
                append("- ").append(task.title)
                task.priority?.let { append(" · priority=").append(it.name.lowercase()) }
                task.estimatedMinutes?.let { append(" · est=").append(it).append("m") }
                if (task.tags.isNotEmpty()) append(" · tags=").append(task.tags.joinToString(",") { t -> t.label })
                append("\n")
            }
        }
    }

    private fun parse(raw: String): TaskDraft? {
        val json = raw.substringAfter('{', "").let { "{$it" }
            .substringBeforeLast('}', "").let { "$it}" }
        if (json.length <= 2) return null
        return runCatching { objectMapper.readValue(json, TaskDraft::class.java) }
            .getOrElse {
                log.warn("suggest_task could not parse sub-agent output: {}", it.message)
                null
            }
    }

    companion object {
        private const val SAMPLE_SIZE = 15

        private val SYSTEM_PROMPT =
            "You draft a single, well-formed backlog task from the user's request, matching the style of " +
                "their existing tasks. Return ONLY a JSON object of the form " +
                "{\"title\":\"...\",\"description\":\"...\"|null,\"category_id\":\"<uuid from the list>\"," +
                "\"priority\":\"low|medium|high\"|null,\"deadline\":\"YYYY-MM-DD\"|null," +
                "\"estimated_minutes\":<int>|null," +
                "\"tags\":[{\"id\":\"<uuid>\"|null,\"label\":\"...\",\"color_id\":\"<color>\"}]}. " +
                "Pick a category_id from the provided list (never invent one). Reuse existing tags by their id " +
                "when they fit; only propose a new tag (id=null) with a color_id from the allowed palette. " +
                "Keep the title short and actionable. Omit optional fields (use null) when the request " +
                "doesn't imply them. Output no prose."
    }
}

data class TaskDraft(
    val title: String,
    val description: String? = null,
    @JsonProperty("category_id") val categoryId: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    @JsonProperty("estimated_minutes") val estimatedMinutes: Int? = null,
    val tags: List<TagDraft> = emptyList(),
)

data class TagDraft(
    val id: String? = null,
    val label: String,
    @JsonProperty("color_id") val colorId: String,
)
