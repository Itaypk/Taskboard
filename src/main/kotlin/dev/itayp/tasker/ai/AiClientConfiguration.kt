package dev.itayp.tasker.ai

import dev.itayp.tasker.ai.client.AiClientProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the OpenRouter client core (package `ai.client`) to the application's own config. The
 * client classes depend only on [AiClientProperties]; this bean maps the app's [AiProperties]
 * onto that contract, keeping the client decoupled from the app's config shape.
 */
@Configuration
class AiClientConfiguration {

    @Bean
    fun aiClientProperties(properties: AiProperties): AiClientProperties =
        AiClientProperties(
            apiKey = properties.apiKey,
            baseUrl = properties.baseUrl,
            configuredModels = properties.configuredModels,
        )
}
