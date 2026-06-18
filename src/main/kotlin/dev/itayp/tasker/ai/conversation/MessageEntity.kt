package dev.itayp.tasker.ai.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.*

@Entity
@Table(name = "ai_message")
class MessageEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "conversation_id", nullable = false)
    var conversationId: UUID? = null

    @Column(nullable = false)
    var role: String? = null

    @Column
    var content: ByteArray? = null

    @Column(name = "tool_calls_json")
    var toolCallsJson: ByteArray? = null

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
