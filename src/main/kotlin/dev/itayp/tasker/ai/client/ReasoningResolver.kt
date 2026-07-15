package dev.itayp.tasker.ai.client

import dev.itayp.tasker.ai.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Resolves the reasoning/effort configuration to apply to a chat request, combining the
 * per-functionality effort config ([AiProperties.reasoning]) with the model's fetched capabilities
 * ([ModelCapabilityService]).
 *
 * Returns null (no reasoning field) when the functionality has no configured effort, the effort
 * value is invalid, or the target model doesn't support reasoning.
 */
@Component
class ReasoningResolver(
    private val properties: AiProperties,
    private val modelCapabilityService: ModelCapabilityService,
) {
    private val log = LoggerFactory.getLogger(ReasoningResolver::class.java)

    fun resolve(conversationType: String, model: String): ReasoningConfig? {
        val configured = effortFor(conversationType)?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (configured.lowercase() !in VALID_EFFORTS) {
            log.warn("Ignoring invalid reasoning effort '{}' for {} (expected one of {})", configured, conversationType, VALID_EFFORTS)
            return null
        }
        if (!modelCapabilityService.supportsReasoning(model)) {
            log.debug("Model {} does not support reasoning; skipping effort={} for {}", model, configured, conversationType)
            return null
        }
        return ReasoningConfig(effort = configured.lowercase())
    }

    private fun effortFor(conversationType: String): String? = when (conversationType) {
        AiConversationType.WEEKLY_PLANNING -> properties.reasoning.weeklyPlanning
        AiConversationType.TASK_SEARCH -> properties.reasoning.taskSearch
        AiConversationType.TASK_SUGGESTION -> properties.reasoning.taskSuggestion
        AiConversationType.SLOT_REMINDER -> properties.reasoning.slotReminder
        else -> null
    }

    companion object {
        private val VALID_EFFORTS = setOf("minimal", "low", "medium", "high")
    }
}
