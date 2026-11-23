package dev.itayp.tasker.channel

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.tasker.ai.AudioTranscriber
import dev.itayp.tasker.ai.InputModalitySupport
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A voice note as an alternative to typing (GitHub issue #233): where the bot is waiting for a typed
 * reply — an answer in the planning conversation, a quick-add clarifying question, an Adjust — a
 * voice note is transcribed, echoed back as "I heard: …", and handed on as the text it says.
 *
 * Not used where a voice note *opens* a capture: that path drafts straight from the transcript in
 * one call and tells the drafting prompt it is reading one (`docs/MULTIMODAL-CAPTURE.md` D4).
 */
@Component
class VoiceInput(
    private val transcriber: AudioTranscriber,
    private val modalitySupport: InputModalitySupport,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(VoiceInput::class.java)

    /** True when [media] is only voice: anything else alongside it is not a typed reply. */
    fun isVoice(media: ChannelInbound.Media): Boolean =
        media.attachments.isNotEmpty() && media.attachments.all { it.kind == AttachmentKind.AUDIO }

    /**
     * The text [media] stands for (its caption, then what was said), as [ChannelInbound.Text]; null
     * when there is nothing to pass on, in which case the user has already been told why and asked
     * to type instead. [context] tags the metric (`planning`, `quickadd`). Callers check [isVoice].
     */
    fun toText(userId: UUID, channel: ConversationChannel, media: ChannelInbound.Media, context: String): ChannelInbound.Text? {
        require(isVoice(media)) { "not a voice-only message" }
        if (!modalitySupport.supports(AttachmentKind.AUDIO)) {
            count(context, "unsupported")
            channel.send(ChannelMessage.Text(msg(userId, "voice.unsupported")))
            return null
        }
        val callContext = AiCallContext(userId = userId.toString(), conversationType = AiConversationType.TRANSCRIPTION)
        val transcript = channel.whileWorking {
            media.attachments.map { transcriber.transcribe(it, callContext) }
        }.filter { it.isNotBlank() }.joinToString("\n\n")
        if (transcript.isBlank()) {
            count(context, "empty")
            channel.send(ChannelMessage.Text(msg(userId, "voice.empty")))
            return null
        }
        count(context, "transcribed")
        log.debug("voice reply transcribed into {} chars, context={}", transcript.length, context)
        channel.send(ChannelMessage.Text(msg(userId, "quickadd.media.heard", channel.formatter.escape(preview(transcript)))))
        val caption = media.caption?.trim().orEmpty()
        return ChannelInbound.Text(listOf(caption, transcript).filter { it.isNotBlank() }.joinToString("\n\n"))
    }

    private fun preview(text: String): String =
        if (text.length <= ECHO_PREVIEW) text else text.take(ECHO_PREVIEW).trimEnd() + "…"

    private fun count(context: String, result: String) =
        meterRegistry.counter("tasker.voice.reply", "context", context, "result", result).increment()

    private fun msg(userId: UUID, key: String, vararg args: Any): String =
        messageSource.getMessage(key, args, userSettingsService.getLocale(userId))

    companion object {
        /** Matches quick-add's echo, so a long voice note reads the same in either place. */
        private const val ECHO_PREVIEW = 200

        const val CONTEXT_PLANNING = "planning"
        const val CONTEXT_QUICK_ADD = "quickadd"
    }
}
