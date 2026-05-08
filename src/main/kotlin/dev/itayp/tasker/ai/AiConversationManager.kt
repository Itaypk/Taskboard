package dev.itayp.tasker.ai

import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.conversation.ConversationService
import dev.itayp.tasker.ai.conversation.toChatMessage
import dev.itayp.tasker.ai.tool.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
    private val aiClient: AiClient,
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
        return conversation.id!!
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
        val storedMessages = conversationService.getMessages(conversationId)
            .map { it.toChatMessage(objectMapper) }
        val systemMessage = conversation.systemPrompt
            ?.let { listOf(ChatMessage(role = "system", content = it)) }
            ?: emptyList()
        val messages = systemMessage + storedMessages

        val request = ChatRequest(
            model = conversation.model!!,
            messages = messages,
            tools = tools,
            temperature = conversation.temperature,
            // TODO: Configure
            maxTokens = 4096,
        )

        val response = aiClient.chat(request)
        val choice = response.choices.first()
        val usage = response.usage

        conversationService.addMessage(
            conversationId = conversationId,
            role = "assistant",
            content = choice.message.content,
            toolCallsJson = choice.message.toolCalls
                ?.let { objectMapper.writeValueAsString(it) },
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
        )

        val toolCalls = choice.message.toolCalls
        return if (!toolCalls.isNullOrEmpty()) {
            TurnOutcome.ToolCalls(toolCalls.map { call ->
                RequestedToolCall(
                    id = call.id,
                    name = call.function.name,
                    arguments = call.function.arguments,
                )
            })
        } else {
            if (choice.message.content.isNullOrBlank()) {
                log.warn("Model returned neither content nor tool_calls for conversation {}", conversationId)
            }
            TurnOutcome.TextReply(choice.message.content ?: "")
        }
    }
}
