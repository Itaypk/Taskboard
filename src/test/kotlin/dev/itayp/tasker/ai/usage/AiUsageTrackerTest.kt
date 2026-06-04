package dev.itayp.tasker.ai.usage

import dev.itayp.tasker.ai.client.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.client.ChatResponse
import dev.itayp.tasker.ai.client.Choice
import dev.itayp.tasker.ai.client.Usage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class AiUsageTrackerTest {

    private val repository: AiUsageEventRepository = mock()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC)
    private val tracker = AiUsageTracker(repository, registry, clock)

    private val userId = UUID.randomUUID()
    private val conversationId = UUID.randomUUID()

    private fun request() = ChatRequest(model = "configured/model", messages = emptyList())

    private fun response(usage: Usage?) = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(ChatMessage(role = "assistant", content = "hi"), finishReason = "stop")),
        usage = usage,
        model = "resolved/model",
        provider = "Anthropic",
    )

    @Test
    fun `success persists a row and increments counters with resolved model and provider`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenAnswer { it.arguments[0] }
        val context = AiCallContext(userId, AiConversationType.WEEKLY_PLANNING, conversationId = conversationId)

        tracker.recordSuccess(context, request(), response(Usage(promptTokens = 100, completionTokens = 40)))

        val captor = argumentCaptor<AiUsageEventEntity>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals(userId, saved.userId)
        assertEquals(AiConversationType.WEEKLY_PLANNING, saved.conversationType)
        assertEquals(conversationId, saved.conversationId)
        assertEquals("resolved/model", saved.model)
        assertEquals("Anthropic", saved.provider)
        assertEquals(100, saved.promptTokens)
        assertEquals(40, saved.completionTokens)
        assertEquals(140, saved.totalTokens)
        assertEquals(AiUsageStatus.SUCCESS, saved.status)
        assertEquals(clock.instant(), saved.createdAt)

        assertEquals(
            1.0,
            registry.counter(
                "tasker.ai.requests",
                "conversation_type", AiConversationType.WEEKLY_PLANNING,
                "model", "resolved/model",
                "provider", "Anthropic",
                "outcome", "success",
            ).count(),
        )
        assertEquals(
            100.0,
            registry.counter(
                "tasker.ai.tokens",
                "conversation_type", AiConversationType.WEEKLY_PLANNING,
                "model", "resolved/model",
                "provider", "Anthropic",
                "type", "prompt",
            ).count(),
        )
    }

    @Test
    fun `failure records an error row with the requested model and no tokens`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenAnswer { it.arguments[0] }
        val context = AiCallContext(userId, AiConversationType.TASK_SEARCH)

        tracker.recordFailure(context, request())

        val captor = argumentCaptor<AiUsageEventEntity>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals(AiUsageStatus.ERROR, saved.status)
        assertEquals("configured/model", saved.model)
        assertEquals(null, saved.promptTokens)
        assertEquals(null, saved.totalTokens)

        assertEquals(
            1.0,
            registry.counter(
                "tasker.ai.requests",
                "conversation_type", AiConversationType.TASK_SEARCH,
                "model", "configured/model",
                "provider", "unknown",
                "outcome", "error",
            ).count(),
        )
    }

    @Test
    fun `a persistence failure does not propagate`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenThrow(RuntimeException("db down"))
        val context = AiCallContext(userId, AiConversationType.TASK_SUGGESTION)

        // Should not throw — accounting is best-effort.
        tracker.recordSuccess(context, request(), response(Usage(promptTokens = 1, completionTokens = 1)))
    }
}
