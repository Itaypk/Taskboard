package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiClientProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * Wires the OpenRouter client core to the application's own config. The client classes depend only
 * on [AiClientProperties]; the [aiClientProperties] bean maps the app's [AiProperties] onto that
 * contract, keeping the client decoupled from the app's config shape.
 *
 * The [ComponentScan] pulls the library's `@Component` beans (`AiClient`, `ModelCapabilityService`,
 * `ReasoningResolver`, `ToolRegistry`) into the context — they live in the library's own package,
 * outside the app's `dev.itayp.tasker` scan base package, so they aren't picked up otherwise.
 */
@Configuration
@ComponentScan("dev.itayp.nescioquid.openrouter")
class AiClientConfiguration {

    @Bean
    fun aiClientProperties(properties: AiProperties): AiClientProperties =
        AiClientProperties(
            apiKey = properties.apiKey,
            baseUrl = properties.baseUrl,
            configuredModels = properties.configuredModels,
        )
}
