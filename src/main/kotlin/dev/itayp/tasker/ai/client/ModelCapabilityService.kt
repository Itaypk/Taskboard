package dev.itayp.tasker.ai.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.tasker.ai.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches, in memory, which of the configured models support the OpenRouter `reasoning` parameter.
 *
 * OpenRouter's model-by-slug endpoint (`GET /model/{slug}`) exposes a `supported_parameters` array;
 * a model that lists `"reasoning"` accepts the reasoning field, otherwise sending it is an illegal
 * argument. The endpoint does not expose a discrete list of allowed effort levels — OpenRouter
 * normalizes `minimal|low|medium|high` across providers — so the only per-model guard we can make is
 * "does this model support reasoning at all", which is what we cache here.
 *
 * The cache is populated once at startup (best-effort). An unfetched/unknown model is treated as
 * *not* supporting reasoning, so we never risk sending an illegal argument.
 */
@Component
class ModelCapabilityService(
    private val properties: AiProperties,
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(ModelCapabilityService::class.java)

    private val client: RestClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.apiKey.trim()}")
        .build()

    // model slug -> capabilities. Absent key means "unknown" (fetch failed or not attempted).
    private val capabilities = ConcurrentHashMap<String, ModelCapabilities>()

    /** Prefetch capabilities for every configured model once the app is up. Never blocks/fails boot. */
    @EventListener(ApplicationReadyEvent)
    fun prefetch() {
        if (properties.apiKey.isBlank()) {
            log.info("AI API key not configured; skipping model capability prefetch")
            return
        }
        for (model in properties.configuredModels) {
            fetch(model)
        }
    }

    /**
     * Whether [model] supports the reasoning parameter. Returns false for any model whose
     * capabilities are unknown (conservative — avoids illegal-argument errors).
     */
    fun supportsReasoning(model: String): Boolean = capabilities[model]?.supportsReasoning == true

    private fun fetch(model: String) {
        try {
            val response = client.get()
                .uri("/model/{slug}", model)
                .retrieve()
                .body(ModelResponse::class.java)
            val supported = response?.data?.supportedParameters ?: emptyList()
            val supportsReasoning = supported.contains("reasoning")
            capabilities[model] = ModelCapabilities(supportsReasoning = supportsReasoning)
            log.info("Fetched model capabilities: model={} supportsReasoning={}", model, supportsReasoning)
        } catch (e: Exception) {
            // Best-effort: leave the model unknown (reasoning omitted) rather than failing startup.
            log.warn("Failed to fetch model capabilities for model={}; reasoning will be omitted", model, e)
        }
    }
}

data class ModelCapabilities(
    val supportsReasoning: Boolean,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ModelResponse(
    val data: ModelData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ModelData(
    @JsonProperty("supported_parameters") val supportedParameters: List<String>? = null,
)
