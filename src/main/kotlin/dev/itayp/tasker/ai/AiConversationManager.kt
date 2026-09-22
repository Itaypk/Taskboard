package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.ChatResponse
import dev.itayp.nescioquid.openrouter.Choice
import dev.itayp.nescioquid.openrouter.ToolCall
import dev.itayp.tasker.ai.conversation.ConversationService
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Drives a single LLM exchange at a time. The manager appends transcript messages,
 * issues one model call per [sendMessage] / [continueConversation] invocation, and
 * returns the [TurnOutcome] for the orchestrator to act on. Tool dispatch — including
 * the multi-step loops needed for data-lookup tools and the suspended-queue semantics
 * of interactive tools — is the orchestrator's job.
 */
@Service
class AiConversationManager(
    private val aiClient: ReasoningAwareAiClient,
    private val conversationService: ConversationService,
    private val toolRegistry: ToolRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiConversationManager::class.java)

    /**
     * Creates a new conversation for [userId] and returns the conversation ID.
     * The system prompt is stored on the conversation entity and prepended at call time.
     */
    fun startConversation(userId: UUID, config: ConversationConfig): UUID {
        val conversation = conversationService.createConversation(userId, config)
        return conversation.id
    }

    /**
     * Appends [userMessage] to the conversation, calls the model once, and returns
     * the resulting outcome.
     */
    fun sendMessage(conversationId: UUID, userMessage: String): TurnOutcome {
        conversationService.addMessage(conversationId, "user", userMessage)
        return invokeModel(conversationId)
    }

    /**
     * Calls the model once with the conversation's existing transcript (no new user
     * message). Used to resume the conversation after the orchestrator has filled in
     * `tool_result` messages — e.g. after a data-lookup, or after the interactive
     * queue from a previous turn has fully drained.
     */
    fun continueConversation(conversationId: UUID): TurnOutcome = invokeModel(conversationId)

    /**
     * Records a `tool_result` message for [toolCallId] in the conversation transcript.
     * Every tool_call_id emitted by the model must have a matching result before the
     * next model call.
     */
    fun recordToolResult(conversationId: UUID, toolCallId: String, toolName: String, result: String) {
        conversationService.addMessage(
            conversationId = conversationId,
            role = "tool",
            content = result,
            toolCallId = toolCallId,
            toolName = toolName,
        )
    }

    private fun invokeModel(conversationId: UUID): TurnOutcome {
        val conversation = conversationService.findById(conversationId)
            ?: error("Conversation $conversationId not found")

        val tools = toolRegistry.toDefinitions().takeIf { it.isNotEmpty() }
        val storedMessages = conversationService.getMessages(conversationId).map { msg ->
            ChatMessage(
                role = msg.role,
                content = msg.content,
                toolCalls = msg.toolCallsJson?.let {
                    objectMapper.readValue(it, object : TypeReference<List<ToolCall>>() {})
                },
                toolCallId = msg.toolCallId,
            )
        }
        // Mark the system prompt as a cache breakpoint: it's the stable prefix reused on every turn
        // of a multi-turn conversation, so caching it saves re-billing the whole prompt each call
        // (notably on models without implicit caching, e.g. Gemini Flash Lite).
        val systemMessage = conversation.systemPrompt
            ?.let { listOf(ChatMessage.cacheable(role = "system", text = it)) }
            ?: emptyList()
        val messages = systemMessage + storedMessages

        val request = ChatRequest(
            model = conversation.model,
            messages = messages,
            tools = tools,
            temperature = conversation.temperature,
            // TODO: Configure
            maxTokens = 4096,
        )

        val context = AiCallContext(
            userId = conversation.userId.toString(),
            conversationType = conversation.conversationType,
            conversationId = conversationId.toString(),
        )
        val response = aiClient.chat(request, context)
        val choice = response.choices.firstOrNull()
        val toolCalls = choice?.message?.toolCalls
        val contentText = choice?.message?.contentText

        // A turn that produced nothing is not appended to the transcript: an assistant message with
        // neither content nor tool calls would be replayed to the provider on every later call, for
        // no gain, and leaving the transcript untouched is what makes the caller's retry a clean
        // re-issue of the identical request. Token accounting still happens — AiUsageTracker writes
        // its own row per call — so only the conversation's rollup misses these (always zero in
        // practice, since a provider that never generated anything bills nothing).
        if (toolCalls.isNullOrEmpty() && contentText.isNullOrBlank()) {
            logEmptyTurn(conversationId, response, choice)
            return TurnOutcome.Empty
        }

        val usage = response.usage
        conversationService.addMessage(
            conversationId = conversationId,
            role = "assistant",
            content = contentText,
            toolCallsJson = toolCalls?.let { objectMapper.writeValueAsString(it) },
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
        )

        return if (!toolCalls.isNullOrEmpty()) {
            TurnOutcome.ToolCalls(toolCalls.map { call ->
                RequestedToolCall(
                    id = call.id,
                    name = call.function.name,
                    arguments = call.function.arguments,
                )
            })
        } else {
            TurnOutcome.TextReply(contentText.orEmpty())
        }
    }

    /**
     * Reports an empty turn with everything that distinguishes a provider fault from a model that
     * chose to say nothing: OpenRouter's normalized `finish_reason`, the provider's own unmapped
     * reason (e.g. Gemini's `MALFORMED_FUNCTION_CALL`), and either error payload. Without these the
     * failure is undiagnosable after the fact — a zero-token call and no explanation.
     */
    private fun logEmptyTurn(conversationId: UUID, response: ChatResponse, choice: Choice?) {
        log.warn(
            "Model returned neither content nor tool_calls for conversation {} " +
                "(generation={}, model={}, provider={}, finishReason={}, nativeFinishReason={}, error={})",
            conversationId,
            response.id,
            response.model,
            response.provider,
            choice?.finishReason,
            choice?.nativeFinishReason,
            choice?.error ?: response.error,
        )
    }
}
