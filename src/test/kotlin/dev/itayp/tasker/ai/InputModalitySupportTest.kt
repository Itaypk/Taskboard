package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ModelCapabilities
import dev.itayp.nescioquid.openrouter.ModelCapabilityService
import dev.itayp.tasker.channel.AttachmentKind
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputModalitySupportTest {

    private val capabilities: ModelCapabilityService = mock()

    private fun support(visionModel: String = "", transcriptionModel: String = "") = InputModalitySupport(
        capabilities,
        AiProperties(
            apiKey = "key",
            taskAssistantModel = "text-model",
            visionModel = visionModel,
            transcriptionModel = transcriptionModel,
        ),
    )

    private fun caps(input: List<String> = listOf("text"), output: List<String> = listOf("text")) =
        ModelCapabilities(supportsReasoning = false, inputModalities = input, outputModalities = output)

    @Test
    fun `images go to the vision model when it takes images`() {
        whenever(capabilities.get("vision-model")).thenReturn(caps(input = listOf("text", "image")))

        val support = support(visionModel = "vision-model")

        assertEquals("vision-model", support.visionModel)
        assertTrue(support.supports(AttachmentKind.IMAGE))
    }

    @Test
    fun `without a vision model, images fall back to the assistant model only if it takes images`() {
        whenever(capabilities.get("text-model")).thenReturn(caps(input = listOf("text")))

        val support = support()

        assertEquals("text-model", support.visionModel)
        assertFalse(support.supports(AttachmentKind.IMAGE))
    }

    @Test
    fun `an image is declined when the vision model's capabilities are unknown`() {
        assertFalse(support(visionModel = "vision-model").supports(AttachmentKind.IMAGE))
    }

    @Test
    fun `PDFs and text files are read by any model`() {
        whenever(capabilities.get("text-model")).thenReturn(caps(input = listOf("text")))

        val support = support()

        assertTrue(support.supportsAll(listOf(AttachmentKind.DOCUMENT, AttachmentKind.TEXT)))
    }

    @Test
    fun `audio needs a configured speech-to-text model, whatever the chat models take`() {
        // A chat model that takes audio input doesn't count: audio is always transcribed now.
        whenever(capabilities.get("text-model")).thenReturn(caps(input = listOf("text", "audio")))

        assertFalse(support().supports(AttachmentKind.AUDIO))
    }

    @Test
    fun `audio is read when the transcription model is a speech-to-text model`() {
        whenever(capabilities.get("stt-model")).thenReturn(caps(input = listOf("audio"), output = listOf("transcription")))

        assertTrue(support(transcriptionModel = "stt-model").supports(AttachmentKind.AUDIO))
    }

    @Test
    fun `audio is declined when the transcription model isn't a speech-to-text model`() {
        whenever(capabilities.get("chat-model")).thenReturn(caps(input = listOf("text", "audio"), output = listOf("text")))

        assertFalse(support(transcriptionModel = "chat-model").supports(AttachmentKind.AUDIO))
    }

    @Test
    fun `a mix is read only when every kind is`() {
        whenever(capabilities.get("vision-model")).thenReturn(caps(input = listOf("text", "image")))

        val support = support(visionModel = "vision-model")

        assertTrue(support.supportsAll(listOf(AttachmentKind.IMAGE, AttachmentKind.DOCUMENT)))
        assertFalse(support.supportsAll(listOf(AttachmentKind.IMAGE, AttachmentKind.AUDIO)))
    }
}
