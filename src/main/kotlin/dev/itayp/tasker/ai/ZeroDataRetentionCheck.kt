package dev.itayp.tasker.ai

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

/**
 * Warns at startup when [AiProperties.zeroDataRetention] is on but a configured model has no
 * zero-data-retention endpoint on OpenRouter. With ZDR enforced, OpenRouter refuses to route such a
 * model anywhere, so every call to it fails — a misconfiguration that otherwise only shows up as AI
 * features breaking at runtime.
 *
 * Runs once, next to the client library's own model-capability prefetch. Advisory only: a failed
 * lookup is logged and never blocks startup. The ZDR endpoint list is public, so no API key is sent.
 */
@Component
class ZeroDataRetentionCheck internal constructor(
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
    private val client: RestClient,
) {

    // Its own client, like the OpenRouter client library's: the app has no RestClient.Builder bean.
    @Autowired
    constructor(aiProperties: AiProperties, objectMapper: ObjectMapper) : this(
        aiProperties,
        objectMapper,
        RestClient.builder()
            .baseUrl(aiProperties.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build())
                    .apply { setReadTimeout(TIMEOUT) },
            )
            .build(),
    )

    private val log = LoggerFactory.getLogger(ZeroDataRetentionCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        if (!aiProperties.zeroDataRetentionInEffect || aiProperties.configuredModels.isEmpty()) return

        val zdrModels = try {
            fetchZdrModels()
        } catch (e: Exception) {
            log.warn("Couldn't fetch OpenRouter's zero-data-retention endpoints, so the configured models weren't checked: {}", e.message)
            return
        }

        val withoutZdr = aiProperties.configuredModels - zdrModels
        if (withoutZdr.isEmpty()) {
            log.debug("Every configured AI model has a zero-data-retention endpoint: {}", aiProperties.configuredModels)
        } else {
            log.warn(
                "TASKER_AI_ZERO_DATA_RETENTION is on, but these configured models have no zero-data-retention " +
                    "endpoint, so every call to them will fail: {}. Pick models listed at {}/endpoints/zdr, or turn " +
                    "zero data retention off.",
                withoutZdr.sorted(), aiProperties.baseUrl,
            )
        }
    }

    /** The model slugs that have at least one ZDR endpoint. */
    private fun fetchZdrModels(): Set<String> {
        val body = client.get().uri("/endpoints/zdr").retrieve().body(String::class.java).orEmpty()
        return objectMapper.readTree(body).path("data").mapNotNull { endpoint ->
            endpoint.path("model_id").asString().takeIf { it.isNotBlank() }
        }.toSet()
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
