package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.TranscriptionClient
import dev.itayp.nescioquid.openrouter.TranscriptionRequest
import dev.itayp.nescioquid.openrouter.TranscriptionResponse
import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.InboundAttachment
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AudioTranscriberTest {

    private val client: TranscriptionClient = mock()
    private val context = AiCallContext(userId = "00000000-0000-0000-0000-000000000001", conversationType = "transcription")
    private val voice = InboundAttachment(AttachmentKind.AUDIO, byteArrayOf(1, 2, 3), "audio/ogg", format = "ogg")

    private fun transcriber(zeroDataRetention: Boolean = true, transcriptionModel: String = "stt-model") = AudioTranscriber(
        client,
        AiProperties(apiKey = "key", transcriptionModel = transcriptionModel, zeroDataRetention = zeroDataRetention),
    )

    @Test
    fun `sends the clip in its own format to the transcription model, and trims the transcript`() {
        whenever(client.transcribe(any(), any())).thenReturn(TranscriptionResponse(text = "  call the plumber\n"))

        val transcript = transcriber().transcribe(voice, context)

        assertEquals("call the plumber", transcript)
        val request = argumentCaptor<TranscriptionRequest>()
        verify(client).transcribe(request.capture(), eq(context))
        assertEquals("stt-model", request.firstValue.model)
        assertEquals("ogg", request.firstValue.inputAudio.format)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), request.firstValue.inputAudio.data)
    }

    @Test
    fun `applies the instance's data-retention setting, off included`() {
        whenever(client.transcribe(any(), any())).thenReturn(TranscriptionResponse(text = "hi"))

        transcriber(zeroDataRetention = false).transcribe(voice, context)

        val request = argumentCaptor<TranscriptionRequest>()
        verify(client).transcribe(request.capture(), any())
        assertFalse(request.firstValue.provider.zdr)
    }

    @Test
    fun `refuses to run without a transcription model`() {
        assertFailsWith<IllegalStateException> { transcriber(transcriptionModel = "").transcribe(voice, context) }
    }
}
