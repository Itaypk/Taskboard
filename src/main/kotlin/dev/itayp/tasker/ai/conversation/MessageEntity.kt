package dev.itayp.tasker.ai.conversation

import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ToolCall
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_message")
open class MessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "conversation_id", nullable = false)
    var conversationId: UUID? = null

    @Column(nullable = false)
    var role: String? = null

    @Column(columnDefinition = "TEXT")
    var content: String? = null

    @Column(name = "tool_calls_json", columnDefinition = "TEXT")
    var toolCallsJson: String? = null

    @Column(name = "tool_call_id")
    var toolCallId: String? = null

    @Column(name = "tool_name")
    var toolName: String? = null

    @Column(nullable = false)
    var position: Int = 0

    @Column(name = "prompt_tokens")
    var promptTokens: Int? = null

    @Column(name = "completion_tokens")
    var completionTokens: Int? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}

fun MessageEntity.toChatMessage(objectMapper: ObjectMapper): ChatMessage {
    val toolCalls = toolCallsJson?.let {
        objectMapper.readValue(it, object : TypeReference<List<ToolCall>>() {})
    }
    return ChatMessage(
        role = role!!,
        content = content,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
    )
}
