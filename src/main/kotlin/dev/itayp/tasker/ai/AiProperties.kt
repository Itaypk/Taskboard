package dev.itayp.tasker.ai

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.ai")
data class AiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** Model slug for the interactive weekly-planning conversation. */
    val weeklyPlanningModel: String = "",
    /** Model slug for the planner's task search/suggestion/reminder sub-agents. */
    val taskAssistantModel: String = "",
    /**
     * Model slug for quick-add captures that carry an image or a voice note. Blank falls back to
     * [taskAssistantModel] — which is only useful if that model itself accepts those modalities;
     * capture is declined (with a message to the user) when the resolved model doesn't.
     */
    val multimodalModel: String = "",
    /** Per-functionality reasoning/effort configuration. */
    val reasoning: AiReasoningProperties = AiReasoningProperties(),
) {
    /** Distinct set of configured model slugs, for the startup capability prefetch. */
    val configuredModels: Set<String>
        get() = setOf(weeklyPlanningModel, taskAssistantModel, multimodalModel)
            .filter { it.isNotBlank() }
            .toSet()
}

/**
 * Reasoning effort per functionality. Each value is an OpenRouter effort level
 * (`minimal|low|medium|high`) or null/blank for "no reasoning override" (default). Keyed by the
 * [dev.itayp.tasker.ai.client.AiConversationType] functionalities; resolved in
 * [dev.itayp.tasker.ai.ReasoningResolver].
 */
data class AiReasoningProperties(
    val weeklyPlanning: String? = null,
    val taskSearch: String? = null,
    val taskSuggestion: String? = null,
    val slotReminder: String? = null,
)
