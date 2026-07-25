package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.ReasoningAwareAiClient
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.ai.parseAssistantJsonResponseOrNull
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
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
    private val aiClient: ReasoningAwareAiClient,
    private val backlogTaskService: BacklogTaskService,
    private val categoryService: BacklogTaskCategoryService,
    private val tagService: BacklogTaskTagService,
    private val userSettingsService: UserSettingsService,
    private val promptTemplateLoader: PromptTemplateLoader,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
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
            maxTokens = MAX_TOKENS,
        )

        val context = AiCallContext(userId = userId, conversationType = AiConversationType.TASK_SUGGESTION)
        val raw = aiClient.chat(request, context).choices.firstOrNull()?.message?.contentText.orEmpty()
        val draft = parseAssistantJsonResponseOrNull(
            objectMapper, raw, TaskDraft::class.java,
            AiConversationType.TASK_SUGGESTION, meterRegistry, log, "suggest_task",
        )
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
        clarifications: List<ClarificationExchange> = emptyList(),
        mustDraft: Boolean = false,
    ): SuggestionOutcome = runQuickAdd(
        userId = userId,
        request = request,
        adjustment = null,
        previousItems = null,
        clarifications = clarifications,
        mustDraft = mustDraft,
    )

    /**
     * Quick-add revision: re-draft [previousItems] given a free-text [instruction] (and any prior
     * clarification Q&A). May itself ask a clarifying question when the instruction is ambiguous,
     * unless [mustDraft] forces a draft.
     */
    fun quickAddRevise(
        userId: UUID,
        request: String,
        previousItems: List<CapturedItem>,
        instruction: String,
        clarifications: List<ClarificationExchange> = emptyList(),
        mustDraft: Boolean = false,
    ): SuggestionOutcome = runQuickAdd(
        userId = userId,
        request = request,
        adjustment = instruction,
        previousItems = previousItems,
        clarifications = clarifications,
        mustDraft = mustDraft,
    )

    private fun runQuickAdd(
        userId: UUID,
        request: String,
        adjustment: String?,
        previousItems: List<CapturedItem>?,
        clarifications: List<ClarificationExchange>,
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
            "previous_draft_block" to (previousItems?.takeIf { it.isNotEmpty() }?.let {
                val rendered = it.map { item ->
                    when (item) {
                        is CapturedItem.Task -> mapOf("kind" to "task") + objectMapper.convertValue(item.draft, Map::class.java)
                        is CapturedItem.Event -> mapOf("kind" to "event") + objectMapper.convertValue(item.draft, Map::class.java)
                    }
                }
                "\nThe current items to revise (JSON):\n${objectMapper.writeValueAsString(rendered)}\n"
            } ?: ""),
            "prior_qa_block" to renderClarifications(clarifications),
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
            maxTokens = MAX_TOKENS,
        )

        val context = AiCallContext(userId = userId, conversationType = AiConversationType.TASK_SUGGESTION)
        val raw = aiClient.chat(chatRequest, context).choices.firstOrNull()?.message?.contentText.orEmpty()
        return parseOutcome(raw)
    }

    private fun parseOutcome(raw: String): SuggestionOutcome {
        val parsed = parseAssistantJsonResponseOrNull(
            objectMapper, raw, QuickAddRaw::class.java,
            AiConversationType.TASK_SUGGESTION, meterRegistry, log, "quick-add",
        ) ?: return SuggestionOutcome.Unparseable

        val question = parsed.clarify?.question?.takeIf { it.isNotBlank() }
        if (question != null) {
            val options = parsed.clarify.options.mapNotNull { opt ->
                val label = opt.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ClarifyOption(id = opt.id?.takeIf { it.isNotBlank() } ?: label, label = label)
            }
            return SuggestionOutcome.Clarify(question = question, options = options)
        }
        val items = parsed.items.orEmpty().mapNotNull { it.toCapturedItem() }
        if (items.isEmpty()) {
            meterRegistry.counter(
                "tasker.ai.parse_failures", "conversation_type", AiConversationType.TASK_SUGGESTION, "reason", "empty_result",
            ).increment()
            log.warn("quick-add output had neither a clarify question nor any captured items")
            return SuggestionOutcome.Unparseable
        }
        return SuggestionOutcome.Draft(items = items)
    }

    private fun renderClarifications(clarifications: List<ClarificationExchange>): String {
        if (clarifications.isEmpty()) return ""
        val lines = clarifications.joinToString("\n") { "Q: ${it.question}\nA: ${it.answer}" }
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

        /**
         * Completion budget for a draft. On reasoning models OpenRouter counts reasoning tokens
         * against `max_tokens`, so this must cover the model's thinking *plus* a multi-item JSON
         * draft — too tight a budget truncated the JSON mid-object (finish_reason=length), which
         * surfaced to the user as "I couldn't turn that into a task".
         */
        private const val MAX_TOKENS = 2048
    }
}

data class TaskDraft(
    val title: String,
    val description: String? = null,
    @JsonProperty("category_id") val categoryId: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    @JsonProperty("estimated_minutes") val estimatedMinutes: Int? = null,
    val tags: List<TagArg> = emptyList(),
)

/**
 * The result of a quick-add drafting/revision call: a non-empty list of captured items, a single
 * clarifying question, or a parse failure. A "draft" outcome can mix tasks and events — the user's
 * request determines what comes out.
 */
sealed interface SuggestionOutcome {
    data class Draft(val items: List<CapturedItem>) : SuggestionOutcome
    data class Clarify(val question: String, val options: List<ClarifyOption>) : SuggestionOutcome
    data object Unparseable : SuggestionOutcome
}

data class ClarifyOption(val id: String, val label: String)

/** One round of clarification (question asked, answer given) carried forward into the next drafting call. */
data class ClarificationExchange(val question: String, val answer: String)

/**
 * Loose binding of the quick-add sub-agent's two possible JSON shapes (an `items` array of typed
 * captured items or a `clarify` object). All fields are optional so a response of either shape
 * deserializes; the agent then decides which shape it actually was.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class QuickAddRaw(
    val clarify: ClarifyRaw? = null,
    val items: List<CapturedItemRaw>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CapturedItemRaw(
    val kind: String? = null,
    // Task fields
    val title: String? = null,
    val description: String? = null,
    @JsonProperty("category_id") val categoryId: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    @JsonProperty("estimated_minutes") val estimatedMinutes: Int? = null,
    val tags: List<TagArg> = emptyList(),
    // Event-only fields
    val start: String? = null,
    val end: String? = null,
    val location: String? = null,
    val notes: String? = null,
) {
    fun toCapturedItem(): CapturedItem? {
        val k = kind?.lowercase()
        if (title.isNullOrBlank()) return null
        return when (k) {
            "event" -> {
                val s = start?.takeIf { it.isNotBlank() } ?: return null
                CapturedItem.Event(EventDraft(title = title, startIso = s, endIso = end, location = location, notes = notes))
            }
            "task", null -> CapturedItem.Task(
                TaskDraft(
                    title = title,
                    description = description,
                    categoryId = categoryId,
                    priority = priority,
                    deadline = deadline,
                    estimatedMinutes = estimatedMinutes,
                    tags = tags,
                ),
            )
            else -> null
        }
    }
}

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
