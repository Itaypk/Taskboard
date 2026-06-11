package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.tasker.ai.client.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.ai.parseAssistantJsonResponse
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
import java.util.Locale
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
        val sample = backlogTaskService.getTasksAcrossBoards(userId, null).take(SAMPLE_SIZE)

        val systemPrompt = promptTemplateLoader.load("task-suggestion/system.md").render(emptyMap())
        val userMessage = promptTemplateLoader.load("task-suggestion/user.md").render(mapOf(
            "request" to description,
            "today" to today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "timezone" to zone.id,
            "user_context" to (contextBlock ?: "(no personal context shared)"),
            "categories" to renderCategories(categories),
            "tags" to renderTags(tags),
            "tag_colors" to TagColor.entries.joinToString(", ") { it.name.lowercase() },
            "task_sample" to renderSample(sample),
        ))
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage),
            ),
            temperature = 0.3,
            maxTokens = 800,
        )

        val context = AiCallContext(userId = userId, conversationType = AiConversationType.TASK_SUGGESTION)
        val raw = aiClient.chat(request, context).choices.firstOrNull()?.message?.content.orEmpty()
        val draft = runCatching { parseAssistantJsonResponse(objectMapper, raw, TaskDraft::class.java) }
            .getOrElse {
                log.warn("suggest_task could not parse sub-agent output: {}", it.message)
                null
            }
        log.debug("suggest_task drafted a task (categories={}, tags={}, sample={})", categories.size, tags.size, sample.size)
        return draft
    }

    /**
     * Quick-add drafting from a free-text request, with optional prior clarification Q&A. Unlike
     * [suggest] (used by the in-session `suggest_task` tool, which always drafts), this may return a
     * single clarifying question when the request is too vague — unless [mustDraft] forces a draft.
     */
    fun quickAddDraft(
        userId: UUID,
        request: String,
        qa: List<QaPair> = emptyList(),
        mustDraft: Boolean = false,
    ): SuggestionOutcome = runQuickAdd(
        userId = userId,
        request = request,
        adjustment = null,
        previousDraft = null,
        qa = qa,
        mustDraft = mustDraft,
    )

    /**
     * Quick-add revision: re-draft [previousDraft] given a free-text [instruction] (and any prior
     * clarification Q&A). May itself ask a clarifying question when the instruction is ambiguous,
     * unless [mustDraft] forces a draft.
     */
    fun quickAddRevise(
        userId: UUID,
        request: String,
        previousDraft: TaskDraft,
        instruction: String,
        qa: List<QaPair> = emptyList(),
        mustDraft: Boolean = false,
    ): SuggestionOutcome = runQuickAdd(
        userId = userId,
        request = request,
        adjustment = instruction,
        previousDraft = previousDraft,
        qa = qa,
        mustDraft = mustDraft,
    )

    private fun runQuickAdd(
        userId: UUID,
        request: String,
        adjustment: String?,
        previousDraft: TaskDraft?,
        qa: List<QaPair>,
        mustDraft: Boolean,
    ): SuggestionOutcome {
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.ofInstant(clock.instant(), zone)
        val contextBlock = settings.contextBlock?.takeIf { it.isNotBlank() }
        val languageName = runCatching {
            Locale.forLanguageTag(settings.preferredLanguage).getDisplayLanguage(Locale.ENGLISH)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "English"

        val categories = categoryService.getAllForUser(userId)
        val tags = tagService.getAllForUser(userId)
        val sample = backlogTaskService.getTasksAcrossBoards(userId, null).take(SAMPLE_SIZE)

        val systemPrompt = promptTemplateLoader.load("task-suggestion/system-clarify.md")
            .render(mapOf("language" to languageName))
        val userMessage = promptTemplateLoader.load("task-suggestion/quickadd-user.md").render(mapOf(
            "request" to request,
            "adjustment_block" to (adjustment?.let { "\nThe user then asked to adjust the draft: $it\n" } ?: ""),
            "previous_draft_block" to (previousDraft?.let {
                "\nThe current draft to revise (JSON):\n${objectMapper.writeValueAsString(it)}\n"
            } ?: ""),
            "prior_qa_block" to renderQa(qa),
            "today" to today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "timezone" to zone.id,
            "user_context" to (contextBlock ?: "(no personal context shared)"),
            "categories" to renderCategories(categories),
            "tags" to renderTags(tags),
            "tag_colors" to TagColor.entries.joinToString(", ") { it.name.lowercase() },
            "task_sample" to renderSample(sample),
            "must_draft_block" to if (mustDraft) {
                "\nYou MUST return a task draft now (shape 1) — do not ask another question."
            } else "",
        ))
        val chatRequest = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage),
            ),
            temperature = 0.3,
            maxTokens = 800,
        )

        val context = AiCallContext(userId = userId, conversationType = AiConversationType.TASK_SUGGESTION)
        val raw = aiClient.chat(chatRequest, context).choices.firstOrNull()?.message?.content.orEmpty()
        return parseOutcome(raw)
    }

    private fun parseOutcome(raw: String): SuggestionOutcome = runCatching {
        val parsed = parseAssistantJsonResponse(objectMapper, raw, QuickAddRaw::class.java)
        val question = parsed.clarify?.question?.takeIf { it.isNotBlank() }
        if (question != null) {
            val options = parsed.clarify.options.mapNotNull { opt ->
                val label = opt.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ClarifyOption(id = opt.id?.takeIf { it.isNotBlank() } ?: label, label = label)
            }
            SuggestionOutcome.Clarify(question = question, options = options)
        } else if (!parsed.title.isNullOrBlank()) {
            SuggestionOutcome.Draft(
                TaskDraft(
                    title = parsed.title,
                    description = parsed.description,
                    categoryId = parsed.categoryId,
                    priority = parsed.priority,
                    deadline = parsed.deadline,
                    estimatedMinutes = parsed.estimatedMinutes,
                    tags = parsed.tags,
                )
            )
        } else {
            log.warn("quick-add output had neither a clarify question nor a task title")
            SuggestionOutcome.Unparseable
        }
    }.getOrElse {
        log.warn("quick-add could not parse sub-agent output: {}", it.message)
        SuggestionOutcome.Unparseable
    }

    private fun renderQa(qa: List<QaPair>): String {
        if (qa.isEmpty()) return ""
        val lines = qa.joinToString("\n") { "Q: ${it.question}\nA: ${it.answer}" }
        return "\nEarlier clarifications in this capture:\n$lines\n"
    }

    private fun renderCategories(categories: List<BacklogTaskCategory>): String {
        if (categories.isEmpty()) return "(none)"
        return categories.joinToString("\n") { "- [${it.id}] ${it.label}" }
    }

    private fun renderTags(tags: List<BacklogTaskTag>): String {
        if (tags.isEmpty()) return "(none yet)"
        return tags.joinToString("\n") { "- [${it.id}] ${it.label} (${it.colorId.name.lowercase()})" }
    }

    private fun renderSample(sample: List<BacklogTask>): String {
        if (sample.isEmpty()) return ""
        val lines = sample.joinToString("\n") { task ->
            buildString {
                append("- ").append(task.title)
                task.priority?.let { append(" · priority=").append(it.name.lowercase()) }
                task.estimatedMinutes?.let { append(" · est=").append(it).append("m") }
                if (task.tags.isNotEmpty()) append(" · tags=").append(task.tags.joinToString(",") { t -> t.label })
            }
        }
        return "\nA sample of the user's existing tasks (for style — match their tone and length):\n$lines"
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

/** The result of a quick-add drafting/revision call: a draft, a clarifying question, or a parse failure. */
sealed interface SuggestionOutcome {
    data class Draft(val draft: TaskDraft) : SuggestionOutcome
    data class Clarify(val question: String, val options: List<ClarifyOption>) : SuggestionOutcome
    data object Unparseable : SuggestionOutcome
}

data class ClarifyOption(val id: String, val label: String)

/** One round of clarification carried forward into the next drafting call. */
data class QaPair(val question: String, val answer: String)

/**
 * Loose binding of the quick-add sub-agent's two possible JSON shapes (a task draft or a
 * `clarify` object). All fields are optional so a response of either shape deserializes; the
 * agent then decides which shape it actually was.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class QuickAddRaw(
    val clarify: ClarifyRaw? = null,
    val title: String? = null,
    val description: String? = null,
    @JsonProperty("category_id") val categoryId: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    @JsonProperty("estimated_minutes") val estimatedMinutes: Int? = null,
    val tags: List<TagDraft> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ClarifyRaw(
    val question: String? = null,
    val options: List<ClarifyOptionRaw> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ClarifyOptionRaw(
    val id: String? = null,
    val label: String? = null,
)
