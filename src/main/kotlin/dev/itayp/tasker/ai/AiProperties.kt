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
     * Model slug for captures that carry images or PDFs (both go to it, in one call). Blank falls
     * back to [taskAssistantModel], which only helps for images if that model itself accepts them;
     * an image capture is declined (with a message to the user) when the resolved model doesn't.
     * PDFs likewise need native file input: OpenRouter's PDF parsers run outside zero data retention.
     */
    val visionModel: String = "",
    /**
     * Speech-to-text model slug for voice notes and audio files, sent to OpenRouter's
     * `/audio/transcriptions`. The transcript then runs the normal text capture against
     * [taskAssistantModel]. No fallback: blank declines audio capture.
     */
    val transcriptionModel: String = "",
    /**
     * Route every LLM call only to provider endpoints with a zero-data-retention policy (OpenRouter's
     * `provider.zdr`). On by default, like the client library's own default: the privacy policy and
     * landing page promise it. A model with no
     * ZDR endpoint then fails every call ("no endpoints found") rather than quietly falling back to one
     * that keeps prompts — check https://openrouter.ai/api/v1/endpoints/zdr when picking models.
     */
    val zeroDataRetention: Boolean = true,
    /** Per-functionality reasoning/effort configuration. */
    val reasoning: AiReasoningProperties = AiReasoningProperties(),
    /** Hard cap on how many accounts may hold a granted AI tier at once. */
    val tierCap: AiTierCapProperties = AiTierCapProperties(),
) {
    /** Whether the instance has an AI provider at all; without one, every AI feature is dark. */
    val enabled: Boolean
        get() = apiKey.isNotBlank()

    /** Whether user-facing copy may promise zero data retention: AI is on, and restricted to ZDR endpoints. */
    val zeroDataRetentionInEffect: Boolean
        get() = enabled && zeroDataRetention

    /**
     * Distinct set of configured model slugs, for the startup capability prefetch and the
     * zero-data-retention check. OpenRouter lists speech-to-text models in both `/model/{slug}` and
     * `/endpoints/zdr`, so the transcription model is checked like any chat model.
     */
    val configuredModels: Set<String>
        get() = setOf(weeklyPlanningModel, taskAssistantModel, visionModel, transcriptionModel)
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

/**
 * Bounds how many *claimed* accounts can hold the full [dev.itayp.tasker.ai.access.AiTier.STANDARD]
 * (or [dev.itayp.tasker.ai.access.AiTier.UNLIMITED]) grant at once — a budget safety valve while the
 * app runs on a prepaid, budget-limited API key. New (claimed) registrations past the cap fall back
 * to [dev.itayp.tasker.ai.access.AiTier.DEMO]'s smaller, fixed budget rather than losing access
 * outright — the cap protects the full allowance, not access itself, so there's no runaway-bill
 * risk in granting the bounded tier unconditionally. Unclaimed demo accounts always start on
 * [dev.itayp.tasker.ai.access.AiTier.DEMO] too, bypassing this cap entirely (see
 * `UserSettingsService.initializeForNewUser`).
 */
data class AiTierCapProperties(
    val maxGrantedUsers: Int = 50,
)
