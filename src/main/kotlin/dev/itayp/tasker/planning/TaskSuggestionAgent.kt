package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.ReasoningAwareAiClient
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.ContentPart
import dev.itayp.tasker.ai.InputModalitySupport
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.ai.parseAssistantJsonResponseOrNull
import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.InboundAttachment
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
    private val inputModalitySupport: InputModalitySupport,
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

        val context = AiCallContext(userId = userId.toString(), conversationType = AiConversationType.TASK_SUGGESTION)
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
        unprompted: Boolean = false,
    ): SuggestionOutcome = runQuickAdd(
        userId = userId,
        request = request,
        adjustment = null,
        previousItems = null,
        clarifications = clarifications,
        mustDraft = mustDraft,
        unprompted = unprompted,
    )

    /**
     * Quick-add drafting from media — a forwarded photo of an invitation, a voice note. The
     * attachments are sent to the multimodal capture model alongside [caption] (whatever text the
     * user sent with them, often nothing); the model reads/listens, writes down what it found in
     * `source_text`, and drafts from that in one call.
     *
     * That `source_text` comes back on the outcome and becomes the capture's `originalRequest`, so
     * every later round of the flow (clarify, adjust, revise) is plain text against the normal
     * model — the bytes are used exactly once and then dropped.
     *
     * Callers must check [InputModalitySupport.supportsAll] first; this throws
     * [UnsupportedModalityException] rather than sending a request OpenRouter would reject.
     */
    fun quickAddDraftFromMedia(
        userId: UUID,
        attachments: List<InboundAttachment>,
        caption: String? = null,
        unprompted: Boolean = false,
    ): SuggestionOutcome {
        require(attachments.isNotEmpty()) { "quickAddDraftFromMedia called with no attachments" }
        if (!inputModalitySupport.supportsAll(attachments.map { it.kind })) {
            throw UnsupportedModalityException(attachments.map { it.kind }.distinct())
        }
        return runQuickAdd(
            userId = userId,
            request = caption?.trim().orEmpty(),
            adjustment = null,
            previousItems = null,
            clarifications = emptyList(),
            mustDraft = false,
            attachments = attachments,
            unprompted = unprompted,
        )
    }

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
        attachments: List<InboundAttachment> = emptyList(),
        unprompted: Boolean = false,
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

        // The third reply shape exists only for a capture the user didn't ask for (`docs/
        // FREE-TEXT-CAPTURE.md` D2): with `/add`, or on any later round of a live capture, the
        // user has already committed to capturing, so a bail-out would only be a way to lose one.
        val systemPrompt = promptTemplateLoader.load("task-suggestion/system-clarify.md").render(mapOf(
            "language" to languageName,
            "not_a_capture_block" to if (unprompted) NOT_A_CAPTURE_BLOCK else "",
        ))
        val userMessage = promptTemplateLoader.load("task-suggestion/quickadd-user.md").render(mapOf(
            "request" to (if (request.isBlank() && attachments.isNotEmpty()) "(no text — see the attachment)" else request),
            "media_block" to renderMediaBlock(attachments),
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
        val userChatMessage = if (attachments.isEmpty()) {
            ChatMessage(role = "user", content = userMessage)
        } else {
            ChatMessage.withAttachments(
                role = "user",
                text = userMessage,
                attachments = attachments.map { it.toContentPart() },
            )
        }
        val chatRequest = ChatRequest(
            model = if (attachments.isEmpty()) model else inputModalitySupport.captureModel,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                userChatMessage,
            ),
            temperature = 0.3,
            maxTokens = MAX_TOKENS,
        )

        val context = AiCallContext(userId = userId.toString(), conversationType = AiConversationType.TASK_SUGGESTION)
        if (attachments.isNotEmpty()) {
            log.debug(
                "quick-add media capture: {} attachment(s) ({}), model={}",
                attachments.size,
                attachments.joinToString(",") { "${it.kind}:${it.bytes.size}B" },
                inputModalitySupport.captureModel,
            )
        }
        val raw = aiClient.chat(chatRequest, context).choices.firstOrNull()?.message?.contentText.orEmpty()
        return parseOutcome(raw, allowNotACapture = unprompted)
    }

    /**
     * The attachment as the wire part its modality needs. Images go as a base64 `data:` URL (we
     * have the bytes already, and a Telegram file URL carries the bot token); audio has no URL form
     * at all on OpenRouter, so base64 is the only option there.
     */
    private fun InboundAttachment.toContentPart(): ContentPart = when (kind) {
        AttachmentKind.IMAGE -> ContentPart.ImageUrl.ofBytes(bytes, mediaType = mediaType)
        AttachmentKind.AUDIO -> ContentPart.InputAudio.ofBytes(bytes, format = format ?: "ogg")
    }

    private fun renderMediaBlock(attachments: List<InboundAttachment>): String {
        if (attachments.isEmpty()) return ""
        val kinds = attachments.map { it.kind }.distinct()
        val what = when {
            kinds == listOf(AttachmentKind.IMAGE) -> if (attachments.size == 1) "an image" else "${attachments.size} images"
            kinds == listOf(AttachmentKind.AUDIO) -> "a voice message"
            else -> "attachments"
        }
        return """

The user sent $what along with (or instead of) the text above. Read/listen to it and capture from
it. Set `source_text` in your reply to a short first-person restatement of what the attachment says
— a transcript for audio, the relevant details for an image — in the user's own language. Keep it to
the part that matters for the capture; the user sees it echoed back, so it must be faithful, not a
summary of your reasoning.
"""
    }

    private fun parseOutcome(raw: String, allowNotACapture: Boolean): SuggestionOutcome {
        val parsed = parseAssistantJsonResponseOrNull(
            objectMapper, raw, QuickAddRaw::class.java,
            AiConversationType.TASK_SUGGESTION, meterRegistry, log, "quick-add",
        ) ?: return SuggestionOutcome.Unparseable

        val sourceText = parsed.sourceText?.trim()?.takeIf { it.isNotBlank() }
        // Ignored unless the shape was offered, so a model that emits it unbidden (mid-capture, or
        // after an `/add`) can't turn a fixable draft into a dead end — it falls through to the
        // clarify/items handling below, and an empty reply ends as Unparseable like any other.
        if (allowNotACapture && parsed.notACapture != null) {
            return SuggestionOutcome.NotACapture(CaptureIntent.parse(parsed.notACapture.intent))
        }
        val question = parsed.clarify?.question?.takeIf { it.isNotBlank() }
        if (question != null) {
            val options = parsed.clarify.options.mapNotNull { opt ->
                val label = opt.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ClarifyOption(id = opt.id?.takeIf { it.isNotBlank() } ?: label, label = label)
            }
            return SuggestionOutcome.Clarify(question = question, options = options, sourceText = sourceText)
        }
        val items = parsed.items.orEmpty().mapNotNull { it.toCapturedItem() }
        if (items.isEmpty()) {
            meterRegistry.counter(
                "tasker.ai.parse_failures", "conversation_type", AiConversationType.TASK_SUGGESTION, "reason", "empty_result",
            ).increment()
            log.warn("quick-add output had neither a clarify question nor any captured items")
            return SuggestionOutcome.Unparseable
        }
        return SuggestionOutcome.Draft(items = items, sourceText = sourceText, planThisWeek = parsed.planThisWeek == true)
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
         * The third reply shape, rendered into the system prompt only for an unprompted capture.
         * Kept here rather than in the template so the template has no dead branch to read past:
         * `system-clarify.md` interpolates this whole block or nothing at all.
         */
        private val NOT_A_CAPTURE_BLOCK = """
- To say this wasn't a capture request at all — the user sent this message on their own, without
  asking to add anything, so it may be something else entirely: a question about their week, a
  request to plan, small talk, or nothing intelligible. Reply with an object whose only key is
  `not_a_capture`, naming what they were actually after:
{"not_a_capture":{"intent":"plan|current|stats|help|unclear"}}
  - `plan` — they want to build or change their weekly plan ("let's plan my week", "move my gym
    session to Thursday", "I need to reschedule Tuesday").
  - `current` — they are *asking about* the plan they already have ("what's on for today?",
    "what did I plan for Thursday?").
  - `stats` — they are asking about their backlog or their numbers ("how many open tasks?").
  - `help` — they are asking what you can do.
  - `unclear` — a greeting, a typo, or anything with no discernible request in it.
  Still prefer capturing: use this shape only when the message clearly isn't something to add to
  the backlog. Something the user has to do is a capture even when phrased as a question, and a
  bare noun ("dentist", "milk") is a capture, not small talk.
""".trim()

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
 * clarifying question, a "this wasn't a capture" verdict, or a parse failure. A "draft" outcome can
 * mix tasks and events — the user's request determines what comes out.
 *
 * [sourceText] is set only for a capture that came from media — it's what the model read in the
 * image or heard in the voice note, and it stands in for the user's typed request from there on.
 */
sealed interface SuggestionOutcome {
    val sourceText: String? get() = null

    data class Draft(
        val items: List<CapturedItem>,
        override val sourceText: String? = null,
        /**
         * The user said this belongs in the current week's plan ("add it to this week", "I want to
         * do this Tuesday"). One half of the gate on the plan hand-off offer
         * (`docs/FREE-TEXT-CAPTURE.md` D6); the other is a deadline that falls inside the week.
         */
        val planThisWeek: Boolean = false,
    ) : SuggestionOutcome

    data class Clarify(
        val question: String,
        val options: List<ClarifyOption>,
        override val sourceText: String? = null,
    ) : SuggestionOutcome

    /**
     * The message wasn't a capture request. Only ever produced for an unprompted capture — the
     * shape isn't offered to the model otherwise (`docs/FREE-TEXT-CAPTURE.md` D2).
     */
    data class NotACapture(val intent: CaptureIntent) : SuggestionOutcome

    data object Unparseable : SuggestionOutcome
}

/**
 * What an unprompted message turned out to want, when it didn't want a capture. Deliberately a
 * small closed set that maps onto the bot's commands; anything the model can't place lands in
 * [UNCLEAR], which is also where an unrecognized value from the model goes.
 */
enum class CaptureIntent {
    PLAN,
    CURRENT,
    STATS,
    HELP,
    UNCLEAR,
    ;

    companion object {
        fun parse(raw: String?): CaptureIntent =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNCLEAR
    }
}

/** Thrown when a media capture is attempted against a model that doesn't accept those modalities. */
class UnsupportedModalityException(val kinds: List<AttachmentKind>) :
    RuntimeException("capture model does not accept input modalities: $kinds")

data class ClarifyOption(val id: String, val label: String)

/** One round of clarification (question asked, answer given) carried forward into the next drafting call. */
data class ClarificationExchange(val question: String, val answer: String)

/**
 * Loose binding of the quick-add sub-agent's possible JSON shapes (an `items` array of typed
 * captured items, a `clarify` object, or — for an unprompted capture only — a `not_a_capture`
 * verdict). All fields are optional so a response of any shape deserializes; the agent then
 * decides which shape it actually was.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class QuickAddRaw(
    val clarify: ClarifyRaw? = null,
    val items: List<CapturedItemRaw>? = null,
    /** Only produced for media captures: what the model read/heard in the attachment. */
    @JsonProperty("source_text") val sourceText: String? = null,
    /** Only offered for an unprompted capture; ignored otherwise. */
    @JsonProperty("not_a_capture") val notACapture: NotACaptureRaw? = null,
    /** Set when the user said the capture belongs in this week's plan. */
    @JsonProperty("plan_this_week") val planThisWeek: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class NotACaptureRaw(val intent: String? = null)

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
