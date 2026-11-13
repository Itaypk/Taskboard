package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ModelCapabilityService
import dev.itayp.tasker.channel.AttachmentKind
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Answers "is there a configured model that can read this?" for a given attachment kind, and names
 * the model each kind goes to (GitHub issue #362):
 *
 * - **Images** go to the [visionModel], which must advertise `image` input. OpenRouter rejects a
 *   modality the target model doesn't accept rather than ignoring it, so an unchecked attach is a 400.
 * - **PDFs** go to the [visionModel] too, so a capture mixing images and PDFs (an email) is one call.
 *   It must advertise `file` input: OpenRouter could parse a PDF for a model without it, but its
 *   parsers run as a plugin, outside zero data retention, so we only use native file input.
 * - **Audio** is transcribed by the [AiProperties.transcriptionModel] (see [AudioTranscriber]), which
 *   must be configured and advertise `transcription` output. The transcript is then plain text.
 * - **Text** files are read as text in the prompt, by any model.
 *
 * Capabilities come from the library's startup prefetch ([ModelCapabilityService]); an unknown model
 * (fetch failed, or the key isn't configured) reports *no* modalities, so we fail closed (and capture
 * from text only) instead of sending a request that would fail.
 */
@Component
class InputModalitySupport(
    private val modelCapabilityService: ModelCapabilityService,
    private val properties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(InputModalitySupport::class.java)

    /** The model images and PDFs are sent to: [AiProperties.visionModel], or the assistant model. */
    val visionModel: String
        get() = properties.visionModel.ifBlank { properties.taskAssistantModel }

    fun supports(kind: AttachmentKind): Boolean = when (kind) {
        AttachmentKind.TEXT -> true
        AttachmentKind.IMAGE -> visionReads(IMAGE)
        AttachmentKind.DOCUMENT -> visionReads(FILE)
        AttachmentKind.AUDIO -> transcriptionAvailable().also {
            if (!it) log.debug("no usable transcription model configured; declining audio capture")
        }
    }

    /** True when every [kinds] entry can be read. Empty input is trivially true. */
    fun supportsAll(kinds: Collection<AttachmentKind>): Boolean = kinds.distinct().all { supports(it) }

    /**
     * Warns about a dedicated capture model that is configured but can't do its job, which would
     * otherwise only show as users' captures being declined. A model whose capabilities are unknown
     * is left alone: the library's prefetch already warned about the failed fetch.
     *
     * Relies on the library's prefetch running first, which it does from `openrouter-client` 0.15.2
     * on (it is ordered ahead of other `ApplicationReadyEvent` listeners); before that, the
     * capabilities may not be in yet and this stays silent.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun warnAboutUnusableModels() {
        if (!properties.enabled) return
        val vision = properties.visionModel
        val visionCaps = vision.takeIf { it.isNotBlank() }?.let { modelCapabilityService.get(it) }
        if (visionCaps != null && IMAGE !in visionCaps.inputModalities) {
            log.warn(
                "TASKER_AI_VISION_MODEL is {}, which doesn't accept image input ({}): image captures will be declined.",
                vision, visionCaps.inputModalities,
            )
        }
        val transcription = properties.transcriptionModel
        val transcriptionCaps = transcription.takeIf { it.isNotBlank() }?.let { modelCapabilityService.get(it) }
        if (transcriptionCaps != null && TRANSCRIPTION !in transcriptionCaps.outputModalities) {
            log.warn(
                "TASKER_AI_TRANSCRIPTION_MODEL is {}, which isn't a speech-to-text model (outputs {}): voice captures will be declined.",
                transcription, transcriptionCaps.outputModalities,
            )
        }
    }

    private fun visionReads(modality: String): Boolean {
        val supported = modelCapabilityService.get(visionModel)?.inputModalities?.contains(modality) == true
        if (!supported) log.debug("model {} does not advertise '{}' input; declining media capture", visionModel, modality)
        return supported
    }

    private fun transcriptionAvailable(): Boolean {
        val model = properties.transcriptionModel
        return model.isNotBlank() &&
            modelCapabilityService.get(model)?.outputModalities?.contains(TRANSCRIPTION) == true
    }

    companion object {
        private const val IMAGE = "image"
        private const val FILE = "file"

        /** The output modality OpenRouter reports for speech-to-text models (`audio->transcription`). */
        private const val TRANSCRIPTION = "transcription"
    }
}
