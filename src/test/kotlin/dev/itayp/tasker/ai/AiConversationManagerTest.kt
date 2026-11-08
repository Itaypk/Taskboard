package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.ChatResponse
import dev.itayp.nescioquid.openrouter.Choice
import dev.itayp.nescioquid.openrouter.Usage
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.conversation.ConversationService
import dev.itayp.tasker.ai.conversation.ConversationStatus
import dev.itayp.tasker.ai.conversation.StoredConversation
import dev.itayp.tasker.ai.conversation.StoredMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

class AiConversationManagerTest {

    private val aiClient: ReasoningAwareAiClient = mock()
    private val conversationService: ConversationService = mock()
    private val manager = AiConversationManager(aiClient, conversationService, ToolRegistry(), ObjectMapper())

    private val conversationId = UUID.randomUUID()

    @BeforeEach
    fun stub() {
        whenever(conversationService.findById(conversationId)).thenReturn(
            StoredConversation(
                id = conversationId,
                userId = UUID.randomUUID(),
                conversationType = AiConversationType.WEEKLY_PLANNING,
                model = "test-model",
                temperature = 0.3,
                status = ConversationStatus.ACTIVE,
                systemPrompt = "system",
            ),
        )
        whenever(conversationService.getMessages(conversationId)).thenReturn(
            listOf(
                StoredMessage(
                    id = UUID.randomUUID(),
                    conversationId = conversationId,
                    role = "user",
                    content = "Looks good, finalize",
                    toolCallsJson = null,
                    toolCallId = null,
                    toolName = null,
                    position = 0,
                ),
            ),
        )
    }

    @Test
    fun `a malformed function call is reported as an empty turn that says so`() {
        whenever(aiClient.chat(any(), any())).thenReturn(emptyResponse(nativeFinishReason = "MALFORMED_FUNCTION_CALL"))

        assertEquals(TurnOutcome.Empty(malformedToolCall = true), manager.continueConversation(conversationId))
    }

    @Test
    fun `an empty turn for any other reason is not flagged as malformed`() {
        whenever(aiClient.chat(any(), any())).thenReturn(emptyResponse(nativeFinishReason = null))

        assertEquals(TurnOutcome.Empty(malformedToolCall = false), manager.continueConversation(conversationId))
    }

    @Test
    fun `the plain retry re-issues the stored transcript unchanged`() {
        whenever(aiClient.chat(any(), any())).thenReturn(emptyResponse(nativeFinishReason = null))

        manager.continueConversation(conversationId)

        assertEquals(listOf("system", "user"), sentRequest().messages.map { it.role })
    }

    @Test
    fun `the malformed-call retry appends a one-off note that is never stored`() {
        whenever(aiClient.chat(any(), any())).thenReturn(
            ChatResponse(
                id = "resp-2",
                choices = listOf(Choice(ChatMessage(role = "assistant", content = "fixed"), finishReason = "stop")),
                usage = Usage(promptTokens = 10, completionTokens = 2),
            ),
        )

        val outcome = manager.continueAfterMalformedToolCall(conversationId)

        assertEquals(TurnOutcome.TextReply("fixed"), outcome)
        val messages = sentRequest().messages
        assertEquals(listOf("system", "user", "user"), messages.map { it.role })
        assertEquals("Looks good, finalize", messages[1].contentText)
        // Only the model's reply is persisted; the note must not become part of the transcript.
        verify(conversationService).addMessage(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(conversationService, never()).addMessage(any(), eq("user"), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    private fun sentRequest(): ChatRequest {
        val captor = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(captor.capture(), any())
        return captor.firstValue
    }

    private fun emptyResponse(nativeFinishReason: String?) = ChatResponse(
        id = "resp-1",
        choices = listOf(
            Choice(
                ChatMessage(role = "assistant", content = ""),
                finishReason = "error",
                nativeFinishReason = nativeFinishReason,
            ),
        ),
        usage = Usage(promptTokens = 0, completionTokens = 0),
        provider = "Google",
    )
}
