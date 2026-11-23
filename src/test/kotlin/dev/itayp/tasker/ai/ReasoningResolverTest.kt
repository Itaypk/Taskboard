package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ModelCapabilities
import dev.itayp.nescioquid.openrouter.ModelCapabilityService
import dev.itayp.tasker.ai.client.AiConversationType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningResolverTest {

    private val capabilities = mock<ModelCapabilityService> {
        on { get("reasoner") } doReturn ModelCapabilities(supportsReasoning = true)
    }

    private fun resolver(reasoning: AiReasoningProperties) =
        ReasoningResolver(AiPropertiesReasoningEffortSource(AiProperties(reasoning = reasoning)), capabilities)

    @Test
    fun `a functionality's own level wins over the default`() {
        val resolver = resolver(AiReasoningProperties(default = "none", weeklyPlanning = "low"))

        assertEquals("low", resolver.resolve(AiConversationType.WEEKLY_PLANNING, "reasoner")?.effort)
    }

    @Test
    fun `the default covers unlisted functionalities and blank levels`() {
        val resolver = resolver(AiReasoningProperties(default = "none", taskSearch = " "))

        assertEquals("none", resolver.resolve("quick_add", "reasoner")?.effort)
        assertEquals("none", resolver.resolve(AiConversationType.TASK_SEARCH, "reasoner")?.effort)
    }

    @Test
    fun `without a default, an unlisted functionality sends no reasoning`() {
        assertNull(resolver(AiReasoningProperties(weeklyPlanning = "low")).resolve("quick_add", "reasoner"))
    }
}
