package dev.itayp.tasker.ai

import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.nescioquid.openrouter.ReasoningEffortSource
import org.springframework.stereotype.Component

/**
 * App-side [ReasoningEffortSource]: maps each known [AiConversationType] to its configured
 * reasoning effort from [AiProperties.reasoning]. This is the application-specific half of
 * reasoning resolution that the generic `ReasoningResolver` (in `ai.client`) delegates to.
 */
@Component
class AiPropertiesReasoningEffortSource(
    private val properties: AiProperties,
) : ReasoningEffortSource {

    override fun effortFor(conversationType: String): String? = when (conversationType) {
        AiConversationType.WEEKLY_PLANNING -> properties.reasoning.weeklyPlanning
        AiConversationType.TASK_SEARCH -> properties.reasoning.taskSearch
        AiConversationType.TASK_SUGGESTION -> properties.reasoning.taskSuggestion
        AiConversationType.SLOT_REMINDER -> properties.reasoning.slotReminder
        else -> null
    }
}
