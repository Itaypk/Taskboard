package dev.itayp.tasker.ai.client

/**
 * The minimal configuration the OpenRouter client core needs, decoupled from the application's
 * own `AiProperties`. The library classes ([AiClient], [ModelCapabilityService]) depend only on
 * this contract; the app supplies it as a bean derived from its own config (see
 * `AiClientConfiguration`).
 */
data class AiClientProperties(
    val apiKey: String,
    val baseUrl: String,
    /** Model slugs to prefetch capabilities for at startup. */
    val configuredModels: Set<String>,
)
