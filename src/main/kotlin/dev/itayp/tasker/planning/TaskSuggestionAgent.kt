package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.ai.safeParseAssistantJsonResponse
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Sub-agent backing the `suggest_task` tool. Drafts a complete, well-formed task from the user's
 * free-text description plus style context — the user's context block, categories, tags, and a sample
 * of their existing tasks — so a brand-new task matches how the user normally writes them. This call
 * only drafts; persistence happens later via `create_task`, after the user approves the draft.
 */
@Service
class TaskSuggestionAgent(
    private val aiClient: AiClient,
    private val backlogTaskService: BacklogTaskService,
    private val categoryService: BacklogTaskCategoryService,
    private val tagService: BacklogTaskTagService,
    private val userSettingsService: UserSettingsService,
    private val promptTemplateLoader: PromptTemplateLoader,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${tasker.ai.task-assistant-model}")
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(TaskSuggestionAgent::class.java)

    fun suggest(userId: UUID, description: String): TaskDraft? {
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.ofInstant(clock.instant(), zone)
        val contextBlock = settings.contextBlock?.takeIf { it.isNotBlank() }

        val categories = categoryService.getAllForUser(userId)
        val tags = tagService.getAllForUser(userId)
        val sample = backlogTaskService.getTasksForUser(userId, null).take(SAMPLE_SIZE)

        val systemPrompt = promptTemplateLoader.load("task-suggestion/system.md").render(emptyMap())
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(
                    role = "user",
                    content = buildUserMessage(description, today, zone, contextBlock, categories, tags, sample),
                ),
            ),
            temperature = 0.3,
            maxTokens = 800,
        )

        val raw = aiClient.chat(request).choices.firstOrNull()?.message?.content.orEmpty()
        val draft = safeParseAssistantJsonResponse(objectMapper, raw, TaskDraft::class.java)
        if (draft == null) log.warn("suggest_task could not parse sub-agent output")
        log.debug("suggest_task drafted a task (categories={}, tags={}, sample={})", categories.size, tags.size, sample.size)
        return draft
    }

    private fun buildUserMessage(
        description: String,
        today: LocalDate,
        zone: ZoneId,
        contextBlock: String?,
        categories: List<BacklogTaskCategory>,
        tags: List<BacklogTaskTag>,
        sample: List<BacklogTask>,
    ): String = buildString {
        append("Draft a task for this request: ").append(description).append("\n\n")

        append("Today is ").append(today.format(DateTimeFormatter.ISO_LOCAL_DATE))
            .append(" (timezone ").append(zone.id).append(").\n\n")

        append("About the user:\n")
        append(contextBlock ?: "(no personal context shared)").append("\n\n")

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

    companion object {
        private const val SAMPLE_SIZE = 15
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
