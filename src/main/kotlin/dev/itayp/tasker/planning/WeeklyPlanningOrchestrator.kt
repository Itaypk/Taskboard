package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.tasker.ai.ConversationConfig
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.planning.dto.AgreedPlan
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the weekly planning interaction. State machine:
 *
 *   START → AWAITING_CAPACITY → CONVERSING → DONE
 *
 * `start` opens a session and emits a capacity Choice.
 * `handleInbound` advances the machine: capacity selection → kicks off the AI conversation;
 * subsequent text inbounds → `AiConversationManager.sendMessage` and the assistant reply is
 * relayed via the channel. When the assistant calls `submit_plan`, the inbox surfaces the
 * agreed plan and the orchestrator completes the session.
 *
 * State lives in-memory keyed by planning session id; fine for v1 dev/testing. Persistence
 * (e.g. across pod restarts) is left for the production iteration alongside the Telegram
 * channel.
 */
@Service
class WeeklyPlanningOrchestrator(
    private val planningSessionService: PlanningSessionService,
    private val promptAssembler: WeeklyPlanningPromptAssembler,
    private val aiConversationManager: AiConversationManager,
    private val planSubmissionInbox: PlanSubmissionInbox,
    @Value("\${tasker.ai.weekly-planning-model:anthropic/claude-sonnet-4.5}")
    private val model: String,
) {
    private val log = LoggerFactory.getLogger(WeeklyPlanningOrchestrator::class.java)

    private val state = ConcurrentHashMap<UUID, OrchestratorState>()

    /**
     * Starts (or rejoins) a planning session for [userId] and emits the opening message
     * (currently the capacity question) to [channel]. Returns the session id.
     */
    fun start(userId: UUID, channel: ConversationChannel): UUID {
        val session = planningSessionService.startSession(userId)
        val sessionId = session.id ?: error("Planning session was saved without an id")

        // If we already had state for this session (e.g. the caller resumed), don't
        // re-emit the capacity question — just return.
        if (state[sessionId] != null) return sessionId

        state[sessionId] = OrchestratorState(
            phase = Phase.AWAITING_CAPACITY,
            userId = userId,
            conversationId = null,
            capacityHint = null,
        )

        channel.send(ChannelMessage.Choice(
            prompt = promptAssembler.renderCapacityQuestion().trim(),
            options = CAPACITY_OPTIONS,
        ))
        return sessionId
    }

    /**
     * Feeds an inbound user reply into the session. The assistant's response (if any) is
     * pushed onto [channel]. Returns the resulting phase so callers can detect completion.
     */
    fun handleInbound(sessionId: UUID, inbound: ChannelInbound, channel: ConversationChannel): Phase {
        val current = state[sessionId]
            ?: throw IllegalStateException("No active orchestrator state for session $sessionId")
        return when (current.phase) {
            Phase.AWAITING_CAPACITY -> handleCapacityReply(sessionId, current, inbound, channel)
            Phase.CONVERSING -> handleConversationTurn(sessionId, current, inbound, channel)
            Phase.DONE -> {
                log.debug("Inbound for already-completed session $sessionId ignored")
                Phase.DONE
            }
        }
    }

    fun phase(sessionId: UUID): Phase? = state[sessionId]?.phase

    fun abandon(userId: UUID, sessionId: UUID) {
        planningSessionService.abandonSession(userId, sessionId)
        state.remove(sessionId)
    }

    private fun handleCapacityReply(
        sessionId: UUID,
        current: OrchestratorState,
        inbound: ChannelInbound,
        channel: ConversationChannel,
    ): Phase {
        val capacity = when (inbound) {
            is ChannelInbound.Selection -> {
                val label = CAPACITY_OPTIONS.firstOrNull { it.id == inbound.optionId }?.label
                    ?: inbound.optionId
                inbound.freeText?.let { "$label ($it)" } ?: label
            }
            is ChannelInbound.Text -> inbound.text
        }

        val systemPrompt = promptAssembler.assembleSystemPrompt(current.userId, capacity)
        log.debug("Weekly planning system prompt for session {}:\n{}", sessionId, systemPrompt)

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

        // Prime with a kickoff user message so the assistant produces the first turn.
        val kickoff = promptAssembler.renderKickoff(capacity).trim()
        relayAssistantTurn(sessionId, conversationId, kickoff, channel)
        return state[sessionId]!!.phase
    }

    private fun handleConversationTurn(
        sessionId: UUID,
        current: OrchestratorState,
        inbound: ChannelInbound,
        channel: ConversationChannel,
    ): Phase {
        val text = when (inbound) {
            is ChannelInbound.Text -> inbound.text
            is ChannelInbound.Selection -> inbound.freeText ?: inbound.optionId
        }
        val conversationId = current.conversationId
            ?: error("CONVERSING phase without a conversation id for session $sessionId")
        relayAssistantTurn(sessionId, conversationId, text, channel)
        return state[sessionId]?.phase ?: Phase.DONE
    }

    private fun relayAssistantTurn(
        sessionId: UUID,
        conversationId: UUID,
        userMessage: String,
        channel: ConversationChannel,
    ) {
        planSubmissionInbox.begin()
        val reply = try {
            aiConversationManager.sendMessage(conversationId, userMessage)
        } catch (e: Exception) {
            planSubmissionInbox.drain()
            throw e
        }
        val submissions = planSubmissionInbox.drain()

        if (reply.isNotBlank()) {
            channel.send(ChannelMessage.Text(reply))
        }

        if (submissions.isNotEmpty()) {
            val plan = submissions.last()
            completeWithPlan(sessionId, plan)
        }
    }

    private fun completeWithPlan(sessionId: UUID, plan: AgreedPlan) {
        val current = state[sessionId] ?: return
        planningSessionService.completeSession(
            userId = current.userId,
            sessionId = sessionId,
            summary = plan.summary,
        )
        state[sessionId] = current.copy(phase = Phase.DONE, agreedPlan = plan)
    }

    enum class Phase { AWAITING_CAPACITY, CONVERSING, DONE }

    data class OrchestratorState(
        val phase: Phase,
        val userId: UUID,
        val conversationId: UUID?,
        val capacityHint: String?,
        val agreedPlan: AgreedPlan? = null,
    )

    companion object {
        const val CONVERSATION_TYPE = "weekly_planning"
        val CAPACITY_OPTIONS = listOf(
            ChoiceOption("light", "Light week"),
            ChoiceOption("normal", "Normal week"),
            ChoiceOption("heavy", "Heavy week — keep it minimal"),
            ChoiceOption("skip", "Prefer to explain in words"),
        )
    }
}
