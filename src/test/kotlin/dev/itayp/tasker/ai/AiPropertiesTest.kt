package dev.itayp.tasker.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPropertiesTest {

    private val compatible = AiProperties(provider = AiProvider.OPENAI_COMPATIBLE, baseUrl = "http://localhost:11434/v1")

    @Test
    fun `OpenRouter needs a key and defaults its base URL`() {
        assertFalse(AiProperties().enabled)
        val openRouter = AiProperties(apiKey = "k")
        assertTrue(openRouter.enabled)
        assertEquals(OPENROUTER_BASE_URL, openRouter.apiBaseUrl)
        assertTrue(openRouter.zeroDataRetentionInEffect)
    }

    @Test
    fun `an OpenAI-compatible server needs a URL but no key`() {
        assertTrue(compatible.enabled)
        assertEquals("http://localhost:11434/v1", compatible.apiBaseUrl)
        val noUrl = AiProperties(provider = AiProvider.OPENAI_COMPATIBLE, apiKey = "k")
        assertFalse(noUrl.enabled)
        assertEquals("", noUrl.apiBaseUrl)
    }

    @Test
    fun `zero data retention is never promised for an OpenAI-compatible server`() {
        assertFalse(compatible.zeroDataRetention)
        assertFalse(compatible.zeroDataRetentionInEffect)
    }

    @Test
    fun `OpenRouter's retention setting can turn the promise off`() {
        val off = AiProperties(apiKey = "k", openrouter = OpenRouterAiProperties(zeroDataRetention = false))
        assertFalse(off.zeroDataRetentionInEffect)
    }
}
