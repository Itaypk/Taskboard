package dev.itayp.tasker.capture

import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.TagInput
import dev.itayp.tasker.planning.ClarifyOption
import dev.itayp.tasker.planning.QaPair
import dev.itayp.tasker.planning.SuggestionOutcome
import dev.itayp.tasker.planning.TagColorOptions
import dev.itayp.tasker.planning.TaskDraft
import dev.itayp.tasker.planning.TaskSuggestionAgent
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * Channel-agnostic "/add" quick-capture flow: drafts a task from a free-text request, shows a
 * confirmation card with Save / Adjust / Cancel, folds free-text adjustments back into a revised
 * draft, and — for genuinely vague input — surfaces up to [MAX_CLARIFY_ROUNDS] bounded clarifying
 * questions before forcing a best-guess draft. Every model call is a single-turn sub-agent call
 * (draft / revise); the turn-taking here is a deterministic state machine, not an LLM conversation.
 *
 * The flow holds no state of its own: callers pass the current [QuickAddState] in and store the
 * returned one (or clear it when null is returned). A channel-specific registry owns that storage
 * and the idle TTL. Renders happen through the supplied [ConversationChannel], so the same flow can
 * back Telegram today and a web surface later.
 */
@Service
class QuickAddFlow(
    private val suggestionAgent: TaskSuggestionAgent,
    private val backlogTaskService: BacklogTaskService,
    private val boardMembershipService: BoardMembershipService,
    private val categoryService: BacklogTaskCategoryService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(QuickAddFlow::class.java)

    /** Opens a flow. [description] is the inline text after `/add`, or null/blank for a bare `/add`. */
    fun begin(userId: UUID, channel: ConversationChannel, description: String?): QuickAddState? {
        val request = description?.trim().orEmpty()
        if (request.isBlank()) {
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.prompt.describe")))
            return QuickAddState.AwaitingDescription(now())
        }
        channel.indicateTyping()
        val op = PendingOp.Draft(request, emptyList())
        val outcome = suggestionAgent.quickAddDraft(userId, request, emptyList(), mustDraft = false)
        return renderOutcome(userId, channel, op, outcome, clarifyRound = 1)
    }

    /** Advances an in-progress flow. Returns the next state, or null when the flow is finished. */
    fun handleInbound(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState,
        inbound: ChannelInbound,
    ): QuickAddState? = when (state) {
        is QuickAddState.AwaitingDescription -> onDescription(userId, channel, inbound)
        is QuickAddState.AwaitingConfirmation -> onConfirmation(userId, channel, state, inbound)
        is QuickAddState.AwaitingAdjustment -> onAdjustment(userId, channel, state, inbound)
        is QuickAddState.AwaitingClarification -> onClarification(userId, channel, state, inbound)
    }

    private fun onDescription(userId: UUID, channel: ConversationChannel, inbound: ChannelInbound): QuickAddState? {
        val text = (inbound as? ChannelInbound.Text)?.text?.trim().orEmpty()
        if (text.isBlank()) {
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.prompt.describe")))
            return QuickAddState.AwaitingDescription(now())
        }
        channel.indicateTyping()
        val op = PendingOp.Draft(text, emptyList())
        val outcome = suggestionAgent.quickAddDraft(userId, text, emptyList(), mustDraft = false)
        return renderOutcome(userId, channel, op, outcome, clarifyRound = 1)
    }

    private fun onConfirmation(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingConfirmation,
        inbound: ChannelInbound,
    ): QuickAddState? {
        if (inbound is ChannelInbound.Selection) {
            return when (inbound.optionId) {
                OPTION_SAVE -> { save(userId, channel, state.draft); null }
                OPTION_CANCEL -> {
                    count("cancelled")
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.cancelled")))
                    null
                }
                OPTION_ADJUST -> {
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.adjust.prompt")))
                    QuickAddState.AwaitingAdjustment(state.draft, state.originalRequest, state.qa, now())
                }
                else -> { reRenderCard(userId, channel, state.draft); state }
            }
        }
        val text = (inbound as? ChannelInbound.Text)?.text?.trim().orEmpty()
        if (text.isBlank()) return state
        return applyAdjustment(userId, channel, state.draft, state.originalRequest, state.qa, text)
    }

    private fun onAdjustment(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingAdjustment,
        inbound: ChannelInbound,
    ): QuickAddState? {
        val text = (inbound as? ChannelInbound.Text)?.text?.trim().orEmpty()
        if (text.isBlank()) {
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.adjust.prompt")))
            return state
        }
        return applyAdjustment(userId, channel, state.draft, state.originalRequest, state.qa, text)
    }

    private fun onClarification(
        userId: UUID,
        channel: ConversationChannel,
        state: QuickAddState.AwaitingClarification,
        inbound: ChannelInbound,
    ): QuickAddState? {
        val answer: String = when (inbound) {
            is ChannelInbound.Selection -> {
                if (inbound.optionId == OPTION_EXPLAIN) {
                    channel.send(ChannelMessage.Text(msg(userId, "quickadd.clarify.explain")))
                    return state.copy(createdAt = now())
                }
                state.options.firstOrNull { it.id == inbound.optionId }?.label ?: inbound.optionId
            }
            is ChannelInbound.Text -> inbound.text.trim()
        }
        if (answer.isBlank()) return state

        val newQa = state.op.qa + QaPair(state.question, answer)
        val mustDraft = state.rounds >= MAX_CLARIFY_ROUNDS
        channel.indicateTyping()
        val (newOp, outcome) = when (val op = state.op) {
            is PendingOp.Draft -> {
                val o = PendingOp.Draft(op.originalRequest, newQa)
                o to suggestionAgent.quickAddDraft(userId, op.originalRequest, newQa, mustDraft)
            }
            is PendingOp.Revise -> {
                val o = PendingOp.Revise(op.originalRequest, op.draft, op.instruction, newQa)
                o to suggestionAgent.quickAddRevise(userId, op.originalRequest, op.draft, op.instruction, newQa, mustDraft)
            }
        }
        return renderOutcome(userId, channel, newOp, outcome, clarifyRound = state.rounds + 1)
    }

    private fun applyAdjustment(
        userId: UUID,
        channel: ConversationChannel,
        draft: TaskDraft,
        originalRequest: String,
        qa: List<QaPair>,
        instruction: String,
    ): QuickAddState? {
        channel.indicateTyping()
        val op = PendingOp.Revise(originalRequest, draft, instruction, qa)
        val outcome = suggestionAgent.quickAddRevise(userId, originalRequest, draft, instruction, qa, mustDraft = false)
        return renderOutcome(userId, channel, op, outcome, clarifyRound = 1)
    }

    private fun renderOutcome(
        userId: UUID,
        channel: ConversationChannel,
        op: PendingOp,
        outcome: SuggestionOutcome,
        clarifyRound: Int,
    ): QuickAddState? = when (outcome) {
        is SuggestionOutcome.Draft -> {
            val validated = validate(userId, outcome.draft)
            renderCard(userId, channel, validated)
            QuickAddState.AwaitingConfirmation(validated, op.originalRequest, op.qa, now())
        }
        is SuggestionOutcome.Clarify -> {
            if (clarifyRound > MAX_CLARIFY_ROUNDS) {
                // The agent asked again despite being told it must draft — give up gracefully.
                log.warn("quick-add exceeded clarification budget; abandoning capture")
                channel.send(ChannelMessage.Text(msg(userId, "quickadd.unparseable")))
                null
            } else {
                // Normalise option ids to short, stable values (they become channel callback data).
                val options = outcome.options.mapIndexed { i, o -> ClarifyOption(id = "o$i", label = o.label) }
                renderClarify(userId, channel, outcome.question, options)
                QuickAddState.AwaitingClarification(op, outcome.question, options, clarifyRound, now())
            }
        }
        is SuggestionOutcome.Unparseable -> {
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.unparseable")))
            null
        }
    }

    private fun save(userId: UUID, channel: ConversationChannel, draft: TaskDraft) {
        val categoryId = draft.categoryId
        if (categoryId.isNullOrBlank()) {
            log.warn("quick-add save aborted: draft had no category")
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.failed")))
            return
        }
        val request = CreateBacklogTaskRequest(
            title = draft.title,
            description = draft.description,
            priority = draft.priority,
            deadline = draft.deadline,
            estimatedMinutes = draft.estimatedMinutes,
            categoryId = categoryId,
            tags = draft.tags.map { TagInput(id = it.id, label = it.label, colorId = TagColorOptions.resolve(it.colorId)) },
        )
        runCatching {
            val boardId = boardMembershipService.resolveDefaultBoard(userId)
            backlogTaskService.createTask(userId, boardId, request)
        }.onSuccess { created ->
            count("saved")
            log.debug("quick-add created backlog task {}", created.id)
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.saved", channel.formatter.escape(created.title))))
        }.onFailure { e ->
            log.warn("quick-add save failed: {}", e.message)
            channel.send(ChannelMessage.Text(msg(userId, "quickadd.failed")))
        }
    }

    /** Drops fields the model may have malformed so the card shows exactly what will be saved. */
    private fun validate(userId: UUID, draft: TaskDraft): TaskDraft {
        val categories = categoryService.getAllForUser(userId)
        val resolvedCategory = draft.categoryId
            ?.let { id -> categories.firstOrNull { it.id.toString() == id } }
            ?: categories.firstOrNull()
        val priority = draft.priority?.lowercase()?.takeIf { it in ALLOWED_PRIORITIES }
        val deadline = draft.deadline?.takeIf { DEADLINE_REGEX.matches(it) }
        val estimate = draft.estimatedMinutes?.takeIf { it > 0 }
        return draft.copy(
            categoryId = resolvedCategory?.id?.toString(),
            priority = priority,
            deadline = deadline,
            estimatedMinutes = estimate,
        )
    }

    private fun reRenderCard(userId: UUID, channel: ConversationChannel, draft: TaskDraft) =
        renderCard(userId, channel, draft)

    private fun renderCard(userId: UUID, channel: ConversationChannel, draft: TaskDraft) {
        val f = channel.formatter
        val locale = locale(userId)
        val categoryLabel = draft.categoryId
            ?.let { id -> categoryService.getAllForUser(userId).firstOrNull { it.id.toString() == id }?.label }

        val meta = buildList {
            categoryLabel?.let { add("🗂 ${f.escape(it)}") }
            draft.priority?.let { add("❗${f.escape(priorityLabel(it, locale))}") }
            draft.deadline?.let { add("📅 ${f.escape(it)}") }
            draft.estimatedMinutes?.let { add("⏱ ${f.escape(messageSource.getMessage("quickadd.card.estimate", arrayOf(it), locale))}") }
        }.joinToString("  ")

        val body = buildString {
            append("➕ ").append(f.bold(draft.title))
            if (meta.isNotEmpty()) append("\n").append(meta)
            draft.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                append("\n🏷 ").append(tags.joinToString(", ") { f.escape(it.label) })
            }
            draft.description?.takeIf { it.isNotBlank() }?.let { desc ->
                append("\n").append(f.italic(truncate(desc)))
            }
        }

        channel.send(
            ChannelMessage.Choice(
                prompt = body,
                options = listOf(
                    ChoiceOption(OPTION_SAVE, msg(userId, "quickadd.button.save")),
                    ChoiceOption(OPTION_ADJUST, msg(userId, "quickadd.button.adjust")),
                    ChoiceOption(OPTION_CANCEL, msg(userId, "quickadd.button.cancel")),
                ),
            )
        )
    }

    private fun renderClarify(
        userId: UUID,
        channel: ConversationChannel,
        question: String,
        options: List<ClarifyOption>,
    ) {
        if (options.isEmpty()) {
            channel.send(ChannelMessage.Text(question))
            return
        }
        val choiceOptions = options.map { ChoiceOption(it.id, it.label) } +
            ChoiceOption(OPTION_EXPLAIN, msg(userId, "quickadd.button.explain"))
        channel.send(ChannelMessage.Choice(prompt = question, options = choiceOptions))
    }

    private fun priorityLabel(priority: String, locale: Locale): String =
        messageSource.getMessage("quickadd.priority.${priority.lowercase()}", null, priority, locale)!!

    private fun truncate(text: String): String =
        if (text.length <= DESCRIPTION_PREVIEW) text else text.take(DESCRIPTION_PREVIEW).trimEnd() + "…"

    private fun count(result: String) =
        meterRegistry.counter("tasker.quickadd.outcome", "result", result).increment()

    private fun locale(userId: UUID): Locale = userSettingsService.getLocale(userId)

    private fun msg(userId: UUID, key: String, vararg args: Any): String =
        messageSource.getMessage(key, args, locale(userId))

    private fun now() = clock.instant()

    companion object {
        /** Maximum clarifying questions the agent may ask before it must produce a best-guess draft. */
        const val MAX_CLARIFY_ROUNDS = 2

        const val OPTION_SAVE = "qa_save"
        const val OPTION_ADJUST = "qa_adjust"
        const val OPTION_CANCEL = "qa_cancel"
        const val OPTION_EXPLAIN = "qa_explain"

        private val ALLOWED_PRIORITIES = setOf("low", "medium", "high")
        private val DEADLINE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private const val DESCRIPTION_PREVIEW = 200
    }
}
