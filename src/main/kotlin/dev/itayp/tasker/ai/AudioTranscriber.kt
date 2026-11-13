package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.ProviderPreferences
import dev.itayp.nescioquid.openrouter.TranscriptionClient
import dev.itayp.nescioquid.openrouter.TranscriptionRequest
import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.InboundAttachment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Turns a voice note or an audio file into text with the configured speech-to-text model
 * ([AiProperties.transcriptionModel]), through OpenRouter's `/audio/transcriptions`.
 *
 * The call goes through the library's gate and listener like a chat call, so the user's AI opt-out
 * and tier still apply. It reports audio seconds rather than tokens, so it doesn't count against the
 * tier's token budget (GitHub issue #362).
 */
@Component
class AudioTranscriber(
    private val transcriptionClient: TranscriptionClient,
    private val properties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(AudioTranscriber::class.java)

    /**
     * The transcript of [audio], trimmed; empty when nothing intelligible was said. Callers check
     * [InputModalitySupport.supports] for [AttachmentKind.AUDIO] first.
     */
    fun transcribe(audio: InboundAttachment, context: AiCallContext): String {
        require(audio.kind == AttachmentKind.AUDIO) { "not an audio attachment: ${audio.kind}" }
        val model = properties.transcriptionModel
        check(model.isNotBlank()) { "no transcription model configured" }

        // No language hint: users switch languages in voice notes, and the models detect it well.
        val request = TranscriptionRequest.ofBytes(model = model, bytes = audio.bytes, format = audio.format ?: "ogg")
            // Set explicitly either way, as ReasoningAwareAiClient does for chat: the library's default is on.
            .copy(provider = ProviderPreferences(zdr = properties.zeroDataRetention))
        val transcript = transcriptionClient.transcribe(request, context).text.trim()
        log.debug("transcribed a {}B {} clip into {} chars, model={}", audio.bytes.size, request.inputAudio.format, transcript.length, model)
        return transcript
    }
}
