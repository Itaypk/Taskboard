package dev.itayp.tasker.ai.client

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.ai.AiReasoningProperties
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningResolverTest {

    private val capabilities: ModelCapabilityService = mock()

    private fun resolver(reasoning: AiReasoningProperties) =
        ReasoningResolver(AiProperties(reasoning = reasoning), capabilities)

    @Test
    fun `returns effort config when configured and model supports reasoning`() {
        whenever(capabilities.supportsReasoning("some/model")).thenReturn(true)
        val resolver = resolver(AiReasoningProperties(taskSearch = "high"))

        val result = resolver.resolve(AiConversationType.TASK_SEARCH, "some/model")

        assertEquals(ReasoningConfig(effort = "high"), result)
    }

    @Test
    fun `returns null when the functionality has no configured effort`() {
        val resolver = resolver(AiReasoningProperties(taskSearch = null))

        assertNull(resolver.resolve(AiConversationType.TASK_SEARCH, "some/model"))
    }

    @Test
    fun `returns null when the model does not support reasoning`() {
        whenever(capabilities.supportsReasoning("some/model")).thenReturn(false)
        val resolver = resolver(AiReasoningProperties(weeklyPlanning = "low"))

        assertNull(resolver.resolve(AiConversationType.WEEKLY_PLANNING, "some/model"))
    }

    @Test
    fun `returns null for an invalid effort value`() {
        val resolver = resolver(AiReasoningProperties(slotReminder = "turbo"))

        assertNull(resolver.resolve(AiConversationType.SLOT_REMINDER, "some/model"))
    }

    @Test
    fun `normalizes and trims the configured effort`() {
        whenever(capabilities.supportsReasoning("some/model")).thenReturn(true)
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
