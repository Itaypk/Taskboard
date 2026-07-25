package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ModelCapabilityService
import dev.itayp.nescioquid.openrouter.ReasoningConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Resolves the reasoning/effort configuration to apply to a chat request, combining the
 * per-functionality effort config ([AiPropertiesReasoningEffortSource]) with the model's fetched
 * capabilities ([ModelCapabilityService]).
 *
 * Returns null (no reasoning field) when the functionality has no configured effort, the effort
 * value isn't accepted by the model, or the target model doesn't support reasoning.
 *
 * This is application-specific policy: the openrouter-client library treats `ChatRequest.reasoning`
 * as caller-controlled and applies no reasoning of its own (see [ReasoningAwareAiClient], which sets
 * it for every planning call).
 */
@Component
class ReasoningResolver(
    private val effortSource: AiPropertiesReasoningEffortSource,
    private val modelCapabilityService: ModelCapabilityService,
) {
    private val log = LoggerFactory.getLogger(ReasoningResolver::class.java)

    fun resolve(conversationType: String, model: String): ReasoningConfig? {
        val configured = effortSource.effortFor(conversationType)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val effort = configured.lowercase()

        val capabilities = modelCapabilityService.get(model)
        if (capabilities == null || !capabilities.supportsReasoning) {
            log.debug("Model {} does not support reasoning; skipping effort={} for {}", model, configured, conversationType)
            return null
        }

        // Validate against the model's own advertised effort levels when it reports them; fall back
        // to the known OpenRouter effort set only when the model didn't advertise supported_efforts.
        val allowed = capabilities.supportedEfforts?.map { it.lowercase() }?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_EFFORTS
        if (effort !in allowed) {
            log.warn("Ignoring reasoning effort '{}' for {} (model {} accepts {})", configured, conversationType, model, allowed)
            return null
        }
        return ReasoningConfig(effort = effort)
    }

    companion object {
        // Fallback effort set used only when a reasoning model doesn't advertise supported_efforts.
        private val DEFAULT_EFFORTS = setOf("minimal", "low", "medium", "high")
    }
}
