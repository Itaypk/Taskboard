package dev.itayp.tasker.ai.conversation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_conversation")
class ConversationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "conversation_type", nullable = false)
    var conversationType: String? = null

    @Column(nullable = false)
    var model: String? = null

    @Column
    var temperature: Double? = null

    @Column(name = "ttl_days", nullable = false)
    var ttlDays: Int = 7

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ConversationStatus = ConversationStatus.ACTIVE

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "last_activity_at", nullable = false)
    var lastActivityAt: Instant? = null

    @Column(name = "soft_deleted_at")
    var softDeletedAt: Instant? = null

    @Column(name = "total_prompt_tokens", nullable = false)
    var totalPromptTokens: Int = 0

    @Column(name = "total_completion_tokens", nullable = false)
    var totalCompletionTokens: Int = 0

    @Column(name = "system_prompt")
    var systemPrompt: ByteArray? = null
}
