package dev.itayp.tasker.ai

import dev.itayp.tasker.ai.client.AiClient
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.conversation.ConversationService
import dev.itayp.tasker.ai.conversation.toChatMessage
import dev.itayp.tasker.ai.tool.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class AiConversationManager(
    private val aiClient: AiClient,
    private val conversationService: ConversationService,
    private val toolRegistry: ToolRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiConversationManager::class.java)

    /**
     * Creates a new conversation for [userId], stores the system prompt as the first message,
     * and returns the conversation ID.
     */
    fun startConversation(userId: UUID, config: ConversationConfig): UUID {
        val conversation = conversationService.createConversation(userId, config)
        conversationService.addMessage(
            conversationId = conversation.id!!,
            role = "system",
            content = config.systemPrompt,
        )
        return conversation.id!!
    }

    /**
     * Appends [userMessage] to the conversation, drives the tool-call loop until the model
     * produces a final text response, and returns that response.
     */
    fun sendMessage(conversationId: UUID, userMessage: String): String {
        val conversation = conversationService.findById(conversationId)
            ?: error("Conversation $conversationId not found")

        conversationService.addMessage(conversationId, "user", userMessage)

        val tools = toolRegistry.toDefinitions().takeIf { it.isNotEmpty() }

        while (true) {
            val messages = conversationService.getMessages(conversationId)
                .map { it.toChatMessage(objectMapper) }

            val request = ChatRequest(
                model = conversation.model!!,
                messages = messages,
                tools = tools,
                temperature = conversation.temperature,
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

            if (choice.finishReason == "tool_calls" && !choice.message.toolCalls.isNullOrEmpty()) {
                choice.message.toolCalls.forEach { toolCall ->
                    val tool = toolRegistry.get(toolCall.function.name)
                    val result = if (tool != null) {
                        runCatching { tool.execute(toolCall.function.arguments) }
                            .getOrElse { e ->
                                log.error("Tool ${toolCall.function.name} threw an exception", e)
                                """{"error": "${e.message}"}"""
                            }
                    } else {
                        log.warn("Model requested unknown tool: ${toolCall.function.name}")
                        """{"error": "Tool '${toolCall.function.name}' is not available"}"""
                    }
                    conversationService.addMessage(
                        conversationId = conversationId,
                        role = "tool",
                        content = result,
                        toolCallId = toolCall.id,
                        toolName = toolCall.function.name,
                    )
                }
                // Loop back to get the model's next response.
            } else {
                return choice.message.content ?: ""
            }
        }
    }
}
