package dev.itayp.tasker.ai.usage

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

/**
 * One row per AI (OpenRouter) call, written right after the call returns (or fails). This is the
 * durable per-user accounting record — the future per-user rate limiter reads it, and it backs
 * any per-user usage reporting that would blow up Prometheus cardinality if done via metrics tags.
 *
 * Contains only metadata and token counts (no prompt/response content), so it is not encrypted.
 */
@Entity
@Table(name = "ai_usage_event")
class AiUsageEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "conversation_type", nullable = false)
    var conversationType: String? = null

    @Column(name = "session_id")
    var sessionId: UUID? = null

    @Column(name = "conversation_id")
    var conversationId: UUID? = null

    @Column(nullable = false)
    var model: String? = null

    @Column
    var provider: String? = null

    @Column(name = "prompt_tokens")
    var promptTokens: Int? = null

    @Column(name = "completion_tokens")
    var completionTokens: Int? = null

    @Column(name = "total_tokens")
    var totalTokens: Int? = null

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: AiUsageStatus = AiUsageStatus.SUCCESS

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}

enum class AiUsageStatus {
    SUCCESS,
    ERROR,
}
