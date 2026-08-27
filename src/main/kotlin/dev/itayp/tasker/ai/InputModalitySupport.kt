package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ModelCapabilityService
import dev.itayp.tasker.channel.AttachmentKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Answers "can the multimodal capture model actually read this?" for a given attachment kind.
 *
 * OpenRouter rejects a modality the target model doesn't accept rather than ignoring it, so every
 * call site that attaches an image or an audio clip must check first. Capabilities come from the
 * library's startup prefetch ([ModelCapabilityService]); an unknown model (fetch failed, or the key
 * isn't configured) reports *no* modalities, so we fail closed and tell the user to type instead of
 * sending a request that would 400.
 */
@Component
class InputModalitySupport(
    private val modelCapabilityService: ModelCapabilityService,
    private val properties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(InputModalitySupport::class.java)

    /** The model media capture is sent to — [AiProperties.multimodalModel], or the assistant model. */
    val captureModel: String
        get() = properties.multimodalModel.takeIf { it.isNotBlank() } ?: properties.taskAssistantModel

    fun supports(kind: AttachmentKind): Boolean {
        val modality = when (kind) {
            AttachmentKind.IMAGE -> "image"
            AttachmentKind.AUDIO -> "audio"
        }
        val supported = modelCapabilityService.get(captureModel)?.inputModalities?.contains(modality) == true
        if (!supported) {
            log.debug("model {} does not advertise '{}' input; declining media capture", captureModel, modality)
        }
        return supported
    }

    /** True when every [kinds] entry is accepted by the capture model. Empty input is trivially true. */
    fun supportsAll(kinds: Collection<AttachmentKind>): Boolean = kinds.distinct().all { supports(it) }
}
