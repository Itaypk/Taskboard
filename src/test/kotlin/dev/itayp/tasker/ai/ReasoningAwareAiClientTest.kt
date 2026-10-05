package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.AiClient
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.ChatResponse
import dev.itayp.nescioquid.openrouter.ProviderPreferences
import dev.itayp.tasker.ai.client.AiConversationType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class ReasoningAwareAiClientTest {

    private val aiClient: AiClient = mock()
    private val reasoningResolver: ReasoningResolver = mock()
    private val context = AiCallContext("user", AiConversationType.WEEKLY_PLANNING)
    private val request = ChatRequest(model = "google/gemini-3.5-flash-lite", messages = listOf(ChatMessage(role = "user", content = "hi")))

    private fun sentRequest(zeroDataRetention: Boolean, request: ChatRequest = this.request): ChatRequest {
        whenever(aiClient.chat(any(), any())).thenReturn(ChatResponse(id = "resp-1", choices = emptyList(), usage = null, model = "resolved/model", provider = "Google"))
        ReasoningAwareAiClient(aiClient, reasoningResolver, AiProperties(zeroDataRetention = zeroDataRetention))
            .chat(request, context)
        val captor = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(captor.capture(), any())
        return captor.firstValue
    }

    @Test
    fun `zero data retention restricts every request to ZDR endpoints`() {
        assertEquals(true, sentRequest(zeroDataRetention = true).provider?.zdr)
    }

    @Test
    fun `zero data retention keeps the caller's other provider preferences`() {
        val sent = sentRequest(
            zeroDataRetention = true,
            request.copy(provider = ProviderPreferences(order = listOf("google-vertex"), allowFallbacks = false)),
        )

        assertEquals(ProviderPreferences(zdr = true, order = listOf("google-vertex"), allowFallbacks = false), sent.provider)
    }

    @Test
    fun `turning zero data retention off overrides the library's default of on`() {
        assertEquals(true, ProviderPreferences().zdr, "library default changed; revisit ReasoningAwareAiClient")
        assertEquals(false, sentRequest(zeroDataRetention = false).provider?.zdr)
    }
}
