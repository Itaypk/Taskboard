package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.tasker.ai.ConversationConfig
import dev.itayp.tasker.ai.RequestedToolCall
import dev.itayp.tasker.ai.TurnOutcome
import dev.itayp.tasker.ai.tool.AiTool
import dev.itayp.tasker.ai.tool.ToolKind
import dev.itayp.tasker.ai.tool.ToolRegistry
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.service.UserSettingsService
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the weekly planning interaction. State machine:
 *
 *   START → AWAITING_CAPACITY → CONVERSING ↔ AWAITING_INTERACTIVE_REPLY → DONE
 *
 * The model speaks via tools (`say`, `ask_choice`, `submit_plan`); the orchestrator
 * dispatches each call by [ToolKind], renders channel messages, queues interactive
 * questions, and decides when to re-invoke the model. See `docs/PLANNING-FLOW.md`.
 */
@Service
class WeeklyPlanningOrchestrator(
    private val planningSessionService: PlanningSessionService,
    private val planFinalizationService: PlanFinalizationService,
    private val promptAssembler: WeeklyPlanningPromptAssembler,
    private val aiConversationManager: AiConversationManager,
    private val planSubmissionInbox: PlanSubmissionInbox,
    private val planningToolContext: PlanningToolContext,
    private val plannedTaskService: PlannedTaskService,
    private val toolRegistry: ToolRegistry,
    private val objectMapper: ObjectMapper,
    private val messageSource: MessageSource,
    private val userSettingsService: UserSettingsService,
    @Value("\${tasker.ai.weekly-planning-model}")
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(WeeklyPlanningOrchestrator::class.java)
    private val state = ConcurrentHashMap<UUID, OrchestratorState>()

    fun start(userId: UUID, channel: ConversationChannel, weekStart: LocalDate): UUID {
        val session = planningSessionService.startSession(userId, weekStart)
        val sessionId = session.id
        if (state[sessionId] != null) return sessionId

        state[sessionId] = OrchestratorState(
            phase = Phase.AWAITING_CAPACITY,
            userId = userId,
            conversationId = null,
            capacityHint = null,
        )
        val settings = userSettingsService.getOrCreate(userId)
        val locale = Locale.forLanguageTag(settings.preferredLanguage)
        val prompt = buildCapacityPrompt(weekStart, locale, channel.formatter)
        channel.send(ChannelMessage.Choice(
            prompt = prompt,
            options = buildCapacityOptions(locale),
        ))
        return sessionId
    }

    fun handleInbound(sessionId: UUID, inbound: ChannelInbound, channel: ConversationChannel): Phase {
        val current = state[sessionId]
            ?: throw IllegalStateException("No active orchestrator state for session $sessionId")
        return when (current.phase) {
            Phase.AWAITING_CAPACITY -> handleCapacityReply(sessionId, current, inbound, channel)
            Phase.CONVERSING -> {
                val text = inboundAsText(inbound)
                channel.indicateTyping()
                val outcome = aiConversationManager.sendMessage(current.conversationId!!, text)
                processOutcome(sessionId, outcome, channel)
                state[sessionId]?.phase ?: Phase.DONE
            }
            Phase.AWAITING_INTERACTIVE_REPLY -> handleInteractiveReply(sessionId, current, inbound, channel)
            Phase.DONE -> Phase.DONE
        }
    }

    /**
     * Starts a revise-in-place conversation for an already-COMPLETED session. Reuses the
     * session id (and its week) so a subsequent [finalizeSubmission] hits the [revisePlan]
     * branch and updates the existing plan in place. Skips capacity entry — revise mode is
     * about editing the current plan, not building a new one.
     */
    fun startRevision(userId: UUID, sessionId: UUID, channel: ConversationChannel): UUID {
        val session = planningSessionService.findById(userId, sessionId)
            ?: throw NoSuchElementException("Planning session $sessionId not found")
        check(session.status == PlanningSessionStatus.COMPLETED) {
            "Cannot revise session $sessionId in status ${session.status}"
        }
        val currentTasks = plannedTaskService.findForSession(userId, sessionId)
        val systemPrompt = promptAssembler.assembleRevisionSystemPrompt(userId, session, currentTasks, channel.formatter)
        log.trace("Weekly planning REVISE system prompt for session {}:\n{}", sessionId, systemPrompt)

        val conversationId = aiConversationManager.startConversation(
            userId = userId,
            config = ConversationConfig(
                conversationType = CONVERSATION_TYPE,
                model = model,
                temperature = 0.3,
                systemPrompt = systemPrompt,
            ),
        )

        state[sessionId] = OrchestratorState(
            phase = Phase.CONVERSING,
            userId = userId,
            conversationId = conversationId,
            capacityHint = null,
        )

        val kickoff = promptAssembler.renderReviseKickoff().trim()
        channel.indicateTyping()
        val outcome = aiConversationManager.sendMessage(conversationId, kickoff)
        processOutcome(sessionId, outcome, channel)
        return sessionId
    }

    fun phase(sessionId: UUID): Phase? = state[sessionId]?.phase

    /** The AI conversation backing this session, or null before the capacity reply creates one. */
    fun conversationId(sessionId: UUID): UUID? = state[sessionId]?.conversationId

    /**
     * Rebuilds the capacity question for a session still in [Phase.AWAITING_CAPACITY], so a web
     * client that reloaded before answering can re-render it (the capacity prompt isn't part of the
     * AI conversation, so it can't be reconstructed from stored messages). Returns null otherwise.
     */
    fun capacityChoice(sessionId: UUID, formatter: MessageFormatter): ChannelMessage.Choice? {
        val current = state[sessionId] ?: return null
        if (current.phase != Phase.AWAITING_CAPACITY) return null
        val weekStart = planningSessionService.findById(current.userId, sessionId)?.weekStart ?: return null
        val locale = userSettingsService.getLocale(current.userId)
        return ChannelMessage.Choice(
            prompt = buildCapacityPrompt(weekStart, locale, formatter),
            options = buildCapacityOptions(locale),
        )
    }

    fun abandon(userId: UUID, sessionId: UUID) {
        // A revise session reuses a COMPLETED session id; abandoning it should drop the
        // in-memory conversation state but never downgrade the persisted plan to ABANDONED.
        val session = planningSessionService.findById(userId, sessionId)
        if (session != null && session.status == PlanningSessionStatus.ACTIVE) {
            planningSessionService.abandonSession(userId, sessionId)
        }
        state.remove(sessionId)
    }

    // ── Capacity -------------------------------------------------------------------------

    private fun handleCapacityReply(
        sessionId: UUID,
        current: OrchestratorState,
        inbound: ChannelInbound,
        channel: ConversationChannel,
    ): Phase {
        val capacity = when (inbound) {
            is ChannelInbound.Selection -> {
                val label = CAPACITY_EN_LABELS[inbound.optionId] ?: inbound.optionId
                inbound.freeText?.let { "$label ($it)" } ?: label
            }
            is ChannelInbound.Text -> inbound.text
        }

        val weekStart = planningSessionService.findById(current.userId, sessionId)?.weekStart
            ?: error("Planning session $sessionId is missing weekStart")
        val systemPrompt = promptAssembler.assembleSystemPrompt(current.userId, capacity, weekStart, channel.formatter)
        log.trace("Weekly planning system prompt for session {}:\n{}", sessionId, systemPrompt)

        val conversationId = aiConversationManager.startConversation(
            userId = current.userId,
            config = ConversationConfig(
                conversationType = CONVERSATION_TYPE,
                model = model,
                temperature = 0.3,
                systemPrompt = systemPrompt,
            ),
        )

        state[sessionId] = current.copy(
            phase = Phase.CONVERSING,
            conversationId = conversationId,
            capacityHint = capacity,
        )

        val kickoff = promptAssembler.renderKickoff(capacity).trim()
        channel.indicateTyping()
        val outcome = aiConversationManager.sendMessage(conversationId, kickoff)
        processOutcome(sessionId, outcome, channel)
        return state[sessionId]?.phase ?: Phase.DONE
    }

    // ── Turn outcome dispatch ------------------------------------------------------------

    private fun processOutcome(sessionId: UUID, outcome: TurnOutcome, channel: ConversationChannel) {
        when (outcome) {
            is TurnOutcome.TextReply -> {
                if (outcome.text.isNotBlank()) {
                    log.warn("Model emitted plain text instead of using a tool. Falling back to render as text.")
                    channel.send(ChannelMessage.Text(outcome.text))
                }
                markConversing(sessionId)
            }
            is TurnOutcome.ToolCalls -> dispatchToolCalls(sessionId, outcome.calls, channel)
        }
    }

    private fun dispatchToolCalls(
        sessionId: UUID,
        calls: List<RequestedToolCall>,
        channel: ConversationChannel,
    ) {
        val current = state[sessionId] ?: error("No orchestrator state for session $sessionId")
        val conversationId = current.conversationId
            ?: error("No conversation id for session $sessionId")

        val pending = mutableListOf<PendingInteractive>()
        var dataLookupRan = false
        var sayRendered = false

        log.debug("Dispatching {} tool call(s) for session {}: {}", calls.size, sessionId, calls.map { it.name })

        planSubmissionInbox.begin()
        planningToolContext.begin(current.userId, resolveZone(current.userId))
        try {
            for (call in calls) {
                val tool = toolRegistry.get(call.name)
                channel.logToolCall(call.name, call.arguments)
                if (tool == null) {
                    log.warn("Model called unknown tool: {}", call.name)
                    aiConversationManager.recordToolResult(
                        conversationId, call.id, call.name,
                        """{"error":"unknown tool"}""",
                    )
                    continue
                }
                when (tool.kind) {
                    ToolKind.ONE_WAY_OUTPUT -> {
                        if (call.name == SAY_TOOL_NAME) sayRendered = true
                        handleOneWay(conversationId, call, tool, channel)
                    }
                    ToolKind.INTERACTIVE_INPUT -> pending.add(parseInteractive(call))
                    ToolKind.DATA_LOOKUP -> {
                        val result = runCatching { tool.execute(call.arguments) }
                            .getOrElse { e ->
                                log.error("Tool ${call.name} threw", e)
                                """{"error":"${e.message}"}"""
                            }
                        aiConversationManager.recordToolResult(conversationId, call.id, call.name, result)
                        dataLookupRan = true
                    }
                }
            }
        } finally {
            planningToolContext.clear()
            val submissions = planSubmissionInbox.drain()
            if (submissions.isNotEmpty()) finalizeSubmission(sessionId, submissions.last())
        }

        if (state[sessionId]?.phase == Phase.DONE) {
            // submit_plan carries its own farewell; render it unless the model already spoke via `say`.
            if (!sayRendered) renderClosingMessage(sessionId, channel)
            return
        }

        if (pending.isNotEmpty()) {
            state[sessionId] = state[sessionId]!!.copy(
                phase = Phase.AWAITING_INTERACTIVE_REPLY,
                pendingInteractive = pending,
            )
            // Render the FIRST pending question now; the rest stay queued until earlier ones resolve.
            channel.send(pending.first().message)
            return
        }

        if (dataLookupRan) {
            channel.indicateTyping()
            val nextOutcome = aiConversationManager.continueConversation(conversationId)
            processOutcome(sessionId, nextOutcome, channel)
            return
        }

        // Only one-way calls fired (or nothing actionable). Wait for next user inbound.
        markConversing(sessionId)
    }

    private fun handleOneWay(
        conversationId: UUID,
        call: RequestedToolCall,
        tool: AiTool,
        channel: ConversationChannel,
    ) {
        when (call.name) {
            SAY_TOOL_NAME -> renderSay(call, channel)
            // submit_plan and any other one-way tools — execute() encapsulates the side effect.
            else -> Unit
        }
        val ack = runCatching { tool.execute(call.arguments) }
            .getOrElse { e ->
                log.error("One-way tool ${call.name} threw", e)
                """{"error":"${e.message}"}"""
            }
        aiConversationManager.recordToolResult(conversationId, call.id, call.name, ack)
    }

    /**
     * Renders the farewell carried on the submitted plan. Falls back to the stored summary if the
     * model left [AgreedPlan.message] blank, so a finalized session is never silent.
     */
    private fun renderClosingMessage(sessionId: UUID, channel: ConversationChannel) {
        val plan = state[sessionId]?.agreedPlan
        if (plan == null) {
            log.warn("No agreed plan to render closing message for session {}", sessionId)
            return
        }
        val message = plan.message?.takeIf { it.isNotBlank() }
            ?: plan.summary.takeIf { it.isNotBlank() }
        if (message == null) {
            log.warn("Finalized session {} has no closing message or summary to render", sessionId)
            return
        }
        log.debug("Rendering closing message for session {} (fromSummary={})", sessionId, plan.message.isNullOrBlank())
        channel.send(ChannelMessage.Text(message))
    }

    private fun renderSay(call: RequestedToolCall, channel: ConversationChannel) {
        val parsed = runCatching { objectMapper.readValue(call.arguments, SayArgs::class.java) }
            .getOrElse {
                log.warn("Failed to parse say args: {}", it.message)
                return
            }
        val completions = parsed.suggestedReplies?.takeIf { channel.capabilities.supportsAutocompletions }
            ?: emptyList()
        channel.send(ChannelMessage.Text(parsed.text, completions))
    }

    private fun parseInteractive(call: RequestedToolCall): PendingInteractive {
        val parsed = objectMapper.readValue(call.arguments, AskChoiceArgs::class.java)
        val options = parsed.options.map { ChoiceOption(it.id, it.label) }
        return PendingInteractive(
            toolCallId = call.id,
            toolName = call.name,
            message = ChannelMessage.Choice(parsed.prompt, options),
            options = options,
        )
    }

    // ── Interactive replies --------------------------------------------------------------

    private fun handleInteractiveReply(
        sessionId: UUID,
        current: OrchestratorState,
        inbound: ChannelInbound,
        channel: ConversationChannel,
    ): Phase {
        val pending = current.pendingInteractive.toMutableList()
        check(pending.isNotEmpty()) { "AWAITING_INTERACTIVE_REPLY with empty queue for $sessionId" }
        val head = pending.removeAt(0)
        val conversationId = current.conversationId!!

        val (resultJson, isEscape) = bindInbound(head, inbound)
        aiConversationManager.recordToolResult(conversationId, head.toolCallId, head.toolName, resultJson)

        if (isEscape) {
            // Drop the rest of the queue, mark each as skipped, and re-invoke immediately.
            for (skipped in pending) {
                aiConversationManager.recordToolResult(
                    conversationId, skipped.toolCallId, skipped.toolName,
                    """{"skipped":true,"reason":"user opted out earlier in the queue"}""",
                )
            }
            state[sessionId] = current.copy(phase = Phase.CONVERSING, pendingInteractive = emptyList())
            channel.indicateTyping()
            val outcome = aiConversationManager.continueConversation(conversationId)
            processOutcome(sessionId, outcome, channel)
            return state[sessionId]?.phase ?: Phase.DONE
        }

        if (pending.isEmpty()) {
            state[sessionId] = current.copy(phase = Phase.CONVERSING, pendingInteractive = emptyList())
            channel.indicateTyping()
            val outcome = aiConversationManager.continueConversation(conversationId)
            processOutcome(sessionId, outcome, channel)
        } else {
            state[sessionId] = current.copy(pendingInteractive = pending)
            channel.send(pending.first().message)
        }
        return state[sessionId]?.phase ?: Phase.DONE
    }

    private fun bindInbound(head: PendingInteractive, inbound: ChannelInbound): Pair<String, Boolean> {
        val (choiceId, label, freeText) = when (inbound) {
            is ChannelInbound.Selection -> {
                val matched = head.options.firstOrNull { it.id == inbound.optionId }
                Triple(inbound.optionId, matched?.label, inbound.freeText)
            }
            is ChannelInbound.Text -> Triple(null, null, inbound.text)
        }
        val isEscape = choiceId == ESCAPE_OPTION_ID
        val payload = mapOf(
            "choice_id" to choiceId,
            "label" to label,
            "free_text" to freeText,
        )
        return objectMapper.writeValueAsString(payload) to isEscape
    }

    // ── Helpers --------------------------------------------------------------------------

    private fun buildCapacityPrompt(weekStart: LocalDate, locale: Locale, formatter: MessageFormatter): String {
        val weekEnd = weekStart.plusDays(6)
        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val period = "${weekStart.format(fmt)} – ${weekEnd.format(fmt)}"
        val question = messageSource.getMessage("planning.capacity.question", null, locale)
        return "${formatter.bold(period)}\n$question"
    }

    private fun buildCapacityOptions(locale: Locale) = listOf(
        ChoiceOption("light", messageSource.getMessage("planning.capacity.option.light", null, locale)),
        ChoiceOption("normal", messageSource.getMessage("planning.capacity.option.normal", null, locale)),
        ChoiceOption("heavy", messageSource.getMessage("planning.capacity.option.heavy", null, locale)),
        ChoiceOption("skip", messageSource.getMessage("planning.capacity.option.skip", null, locale)),
    )

    private fun finalizeSubmission(sessionId: UUID, plan: AgreedPlan) {
        val current = state[sessionId] ?: return
        val session = planningSessionService.findById(current.userId, sessionId)
        val revising = session?.status == PlanningSessionStatus.COMPLETED
        log.debug(
            "Finalizing submission for session {} (revising={}, tasks={})",
            sessionId, revising, plan.tasks.size,
        )
        if (revising) {
            planFinalizationService.revisePlan(current.userId, sessionId, plan)
        } else {
            planFinalizationService.complete(current.userId, sessionId, plan)
        }
        state[sessionId] = current.copy(phase = Phase.DONE, agreedPlan = plan)
    }

    private fun resolveZone(userId: UUID): java.time.ZoneId =
        runCatching { java.time.ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(java.time.ZoneId.of("UTC"))

    private fun markConversing(sessionId: UUID) {
        val current = state[sessionId] ?: return
        if (current.phase != Phase.DONE && current.phase != Phase.AWAITING_INTERACTIVE_REPLY) {
            state[sessionId] = current.copy(phase = Phase.CONVERSING)
        }
    }

    private fun inboundAsText(inbound: ChannelInbound): String = when (inbound) {
        is ChannelInbound.Text -> inbound.text
        is ChannelInbound.Selection -> inbound.freeText ?: inbound.optionId
    }

    enum class Phase { AWAITING_CAPACITY, CONVERSING, AWAITING_INTERACTIVE_REPLY, DONE }

    data class OrchestratorState(
        val phase: Phase,
        val userId: UUID,
        val conversationId: UUID?,
        val capacityHint: String?,
        val agreedPlan: AgreedPlan? = null,
        val pendingInteractive: List<PendingInteractive> = emptyList(),
    )

    data class PendingInteractive(
        val toolCallId: String,
        val toolName: String,
        val message: ChannelMessage.Choice,
        val options: List<ChoiceOption>,
    )

    private data class SayArgs(
        val text: String,
        @JsonProperty("suggested_replies") val suggestedReplies: List<String>? = null,
    )

    private data class AskChoiceArgs(
        val prompt: String,
        val options: List<ChoiceOptionArg>,
    )

    private data class ChoiceOptionArg(
        val id: String,
        val label: String,
    )

    companion object {
        const val CONVERSATION_TYPE = "weekly_planning"
        const val SAY_TOOL_NAME = "say"
        const val ESCAPE_OPTION_ID = "discuss"

        // Canonical English labels used as capacity context for the LLM (language-independent).
        private val CAPACITY_EN_LABELS = mapOf(
            "light" to "Light week",
            "normal" to "Normal week",
            "heavy" to "Heavy week — keep it minimal",
        )
    }
}
