package dev.itayp.tasker.ai

import dev.itayp.tasker.ai.client.AiConversationType
import org.springframework.stereotype.Component

/**
 * Maps each known [AiConversationType] to its configured reasoning effort from
 * [AiProperties.reasoning]. This is the application-specific half of reasoning resolution that
 * [ReasoningResolver] delegates to.
 */
@Component
class AiPropertiesReasoningEffortSource(
    private val properties: AiProperties,
) {

    /** The functionality's own level, or [AiReasoningProperties.default] when it has none (or it's blank). */
    fun effortFor(conversationType: String): String? {
        val own = when (conversationType) {
            AiConversationType.WEEKLY_PLANNING -> properties.reasoning.weeklyPlanning
            AiConversationType.TASK_SEARCH -> properties.reasoning.taskSearch
            AiConversationType.TASK_SUGGESTION -> properties.reasoning.taskSuggestion
            AiConversationType.SLOT_REMINDER -> properties.reasoning.slotReminder
            else -> null
        }
        return own?.takeIf { it.isNotBlank() } ?: properties.reasoning.default
    }
}
