package dev.itayp.tasker.ai.usage

import dev.itayp.tasker.ai.client.AiCallContext
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.client.ChatMessage
import dev.itayp.tasker.ai.client.ChatRequest
import dev.itayp.tasker.ai.client.ChatResponse
import dev.itayp.tasker.ai.client.Choice
import dev.itayp.tasker.ai.client.ModelCapabilities
import dev.itayp.tasker.ai.client.ModelCapabilityService
import dev.itayp.tasker.ai.client.PromptTokensDetails
import dev.itayp.tasker.ai.client.ReasoningConfig
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
    private val modelCapabilityService: ModelCapabilityService = mock()
    private val tracker = AiUsageTracker(repository, registry, clock, modelCapabilityService)

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
                "effort", "none",
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
                "effort", "none",
                "type", "prompt",
            ).count(),
        )
    }

    @Test
    fun `success emits cached and cache_write token counters when prompt_tokens_details is present`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenAnswer { it.arguments[0] }
        val context = AiCallContext(userId, AiConversationType.WEEKLY_PLANNING, conversationId = conversationId)

        tracker.recordSuccess(
            context,
            request(),
            response(
                Usage(
                    promptTokens = 100,
                    completionTokens = 40,
                    promptTokensDetails = PromptTokensDetails(cachedTokens = 64, cacheWriteTokens = 12),
                ),
            ),
        )

        assertEquals(
            64.0,
            registry.counter(
                "tasker.ai.tokens",
                "conversation_type", AiConversationType.WEEKLY_PLANNING,
                "model", "resolved/model",
                "provider", "Anthropic",
                "effort", "none",
                "type", "cached",
            ).count(),
        )
        assertEquals(
            12.0,
            registry.counter(
                "tasker.ai.tokens",
                "conversation_type", AiConversationType.WEEKLY_PLANNING,
                "model", "resolved/model",
                "provider", "Anthropic",
                "effort", "none",
                "type", "cache_write",
            ).count(),
        )
    }

    @Test
    fun `success without prompt_tokens_details emits no cache counters`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenAnswer { it.arguments[0] }
        val context = AiCallContext(userId, AiConversationType.WEEKLY_PLANNING, conversationId = conversationId)

        tracker.recordSuccess(context, request(), response(Usage(promptTokens = 100, completionTokens = 40)))

        // No cached/cache_write series should have been created.
        assertEquals(
            0,
            registry.find("tasker.ai.tokens").tag("type", "cached").counters().size,
        )
        assertEquals(
            0,
            registry.find("tasker.ai.tokens").tag("type", "cache_write").counters().size,
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
                "effort", "none",
                "outcome", "error",
            ).count(),
        )
    }

    @Test
    fun `effort label reflects the request's reasoning effort`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenAnswer { it.arguments[0] }
        val context = AiCallContext(userId, AiConversationType.WEEKLY_PLANNING, conversationId = conversationId)
        val request = ChatRequest(
            model = "configured/model",
            messages = emptyList(),
            reasoning = ReasoningConfig(effort = "low"),
        )

        tracker.recordSuccess(context, request, response(Usage(promptTokens = 5, completionTokens = 2)))

        assertEquals(
            1.0,
            registry.counter(
                "tasker.ai.requests",
                "conversation_type", AiConversationType.WEEKLY_PLANNING,
                "model", "resolved/model",
                "provider", "Anthropic",
                "effort", "low",
                "outcome", "success",
            ).count(),
        )
    }

    @Test
    fun `effort label falls back to the model default effort for a reasoning model`() {
        whenever(repository.save(any<AiUsageEventEntity>())).thenAnswer { it.arguments[0] }
        whenever(modelCapabilityService.get("configured/model"))
            .thenReturn(ModelCapabilities(supportsReasoning = true, defaultEffort = "minimal"))
        val context = AiCallContext(userId, AiConversationType.WEEKLY_PLANNING, conversationId = conversationId)

        // No explicit reasoning on the request → label reflects the model's default effort.
        tracker.recordSuccess(context, request(), response(Usage(promptTokens = 5, completionTokens = 2)))

        assertEquals(
            1.0,
            registry.counter(
                "tasker.ai.requests",
                "conversation_type", AiConversationType.WEEKLY_PLANNING,
                "model", "resolved/model",
                "provider", "Anthropic",
                "effort", "minimal",
                "outcome", "success",
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
