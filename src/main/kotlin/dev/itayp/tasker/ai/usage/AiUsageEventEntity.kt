package dev.itayp.tasker.ai.usage

import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.*

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
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
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

    /**
     * The call itself succeeded — HTTP 200, no exception — but the model produced neither content
     * nor tool calls, so nothing usable came back. Its own status rather than [SUCCESS] because
     * that is a failure from every caller's point of view, and folding it into SUCCESS is what hid
     * it: the `outcome` tag on `tasker.ai.requests` is the only thing that makes it alertable.
     */
    EMPTY,
}
