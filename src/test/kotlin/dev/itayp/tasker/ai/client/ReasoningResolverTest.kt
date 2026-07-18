package dev.itayp.tasker.ai.client

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.ai.AiPropertiesReasoningEffortSource
import dev.itayp.tasker.ai.AiReasoningProperties
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningResolverTest {

    private val capabilities: ModelCapabilityService = mock()

    private fun resolver(reasoning: AiReasoningProperties) =
        ReasoningResolver(AiPropertiesReasoningEffortSource(AiProperties(reasoning = reasoning)), capabilities)

    private fun caps(supportsReasoning: Boolean, supportedEfforts: List<String>? = null) =
        ModelCapabilities(supportsReasoning = supportsReasoning, supportedEfforts = supportedEfforts)

    @Test
    fun `returns effort config when configured and model supports reasoning`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = true))
        val resolver = resolver(AiReasoningProperties(taskSearch = "high"))

        val result = resolver.resolve(AiConversationType.TASK_SEARCH, "some/model")

        assertEquals(ReasoningConfig(effort = "high"), result)
    }

    @Test
    fun `validates against the model's advertised supported efforts`() {
        whenever(capabilities.get("some/model"))
            .thenReturn(caps(supportsReasoning = true, supportedEfforts = listOf("low", "medium")))
        val resolver = resolver(AiReasoningProperties(taskSearch = "high"))

        // "high" is a valid OpenRouter effort but not advertised by this model → rejected.
        assertNull(resolver.resolve(AiConversationType.TASK_SEARCH, "some/model"))
    }

    @Test
    fun `accepts an effort within the model's advertised supported efforts`() {
        whenever(capabilities.get("some/model"))
            .thenReturn(caps(supportsReasoning = true, supportedEfforts = listOf("low", "medium")))
        val resolver = resolver(AiReasoningProperties(taskSearch = "medium"))

        assertEquals(
            ReasoningConfig(effort = "medium"),
            resolver.resolve(AiConversationType.TASK_SEARCH, "some/model"),
        )
    }

    @Test
    fun `returns null when the functionality has no configured effort`() {
        val resolver = resolver(AiReasoningProperties(taskSearch = null))

        assertNull(resolver.resolve(AiConversationType.TASK_SEARCH, "some/model"))
    }

    @Test
    fun `returns null when the model does not support reasoning`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = false))
        val resolver = resolver(AiReasoningProperties(weeklyPlanning = "low"))

        assertNull(resolver.resolve(AiConversationType.WEEKLY_PLANNING, "some/model"))
    }

    @Test
    fun `returns null when the model is unknown`() {
        whenever(capabilities.get("some/model")).thenReturn(null)
        val resolver = resolver(AiReasoningProperties(weeklyPlanning = "low"))

        assertNull(resolver.resolve(AiConversationType.WEEKLY_PLANNING, "some/model"))
    }

    @Test
    fun `returns null for an invalid effort value when the model advertises no efforts`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = true))
        val resolver = resolver(AiReasoningProperties(slotReminder = "turbo"))

        assertNull(resolver.resolve(AiConversationType.SLOT_REMINDER, "some/model"))
    }

    @Test
    fun `normalizes and trims the configured effort`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = true))
        val resolver = resolver(AiReasoningProperties(taskSuggestion = " Medium "))

        assertEquals(
            ReasoningConfig(effort = "medium"),
            resolver.resolve(AiConversationType.TASK_SUGGESTION, "some/model"),
        )
    }

    @Test
    fun `returns null for an unknown conversation type`() {
        val resolver = resolver(AiReasoningProperties(taskSearch = "high"))

        assertNull(resolver.resolve("unknown_type", "some/model"))
    }
}
