package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiClientProperties
import dev.itayp.nescioquid.openrouter.ApiDialect
import dev.itayp.nescioquid.openrouter.ModelCapabilities
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * Wires the OpenRouter client core to the application's own config. The client classes depend only
 * on [AiClientProperties]; the [aiClientProperties] bean maps the app's [AiProperties] onto that
 * contract, keeping the client decoupled from the app's config shape.
 *
 * The [ComponentScan] pulls the library's `@Component` beans (`AiClient`, `ModelCapabilityService`,
 * `ToolRegistry`) into the context — they live in the library's own package, outside the app's
 * `dev.itayp.tasker` scan base package, so they aren't picked up otherwise. (Reasoning resolution is
 * app-side now — see `ReasoningResolver` / `ReasoningAwareAiClient`.)
 */
@Configuration
@ComponentScan("dev.itayp.nescioquid.openrouter")
class AiClientConfiguration {

    @Bean
    fun aiClientProperties(properties: AiProperties): AiClientProperties =
        AiClientProperties(
            apiKey = properties.apiKey,
            baseUrl = properties.apiBaseUrl,
            configuredModels = properties.configuredModels,
            dialect = when (properties.provider) {
                AiProvider.OPENROUTER -> ApiDialect.OPENROUTER
                AiProvider.OPENAI_COMPATIBLE -> ApiDialect.OPENAI_COMPATIBLE
            },
            declaredCapabilities = declaredCapabilities(properties),
            // Only drives the library's startup check that every configured model has a ZDR endpoint;
            // ReasoningAwareAiClient is what applies the setting to each request.
            zeroDataRetention = properties.zeroDataRetention,
        )

    companion object {
        /**
         * What the operator says the models on an OpenAI-compatible server can do, which is all the
         * library knows about them there (OpenRouter's are fetched instead). The transcription model
         * gets nothing, so voice capture stays declined: the client can't transcribe there yet.
         */
        fun declaredCapabilities(properties: AiProperties): Map<String, ModelCapabilities> {
            if (properties.provider != AiProvider.OPENAI_COMPATIBLE) return emptyMap()
            val compatible = properties.openaiCompatible
            val visionModel = properties.visionModel.ifBlank { properties.taskAssistantModel }
            val visionModalities = compatible.visionInputs.mapNotNull { VISION_INPUT_MODALITIES[it.trim().lowercase()] }
            return (properties.configuredModels - properties.transcriptionModel).associateWith { model ->
                ModelCapabilities(
                    supportsReasoning = compatible.reasoning,
                    inputModalities = listOf("text") + if (model == visionModel) visionModalities else emptyList(),
                )
            }
        }

        /** `TASKER_AI_OPENAI_COMPATIBLE_VISION_INPUTS` values, to the modality names the capture code checks. */
        private val VISION_INPUT_MODALITIES = mapOf("image" to "image", "pdf" to "file")
    }
}
