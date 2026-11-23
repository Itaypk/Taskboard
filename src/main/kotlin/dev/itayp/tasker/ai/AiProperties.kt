package dev.itayp.tasker.ai

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.ai")
data class AiProperties(
    /** Which API [baseUrl] speaks (GitHub issue #331). */
    val provider: AiProvider = AiProvider.OPENROUTER,
    /** Required by OpenRouter; optional for an OpenAI-compatible server that needs no key. */
    val apiKey: String = "",
    /** The API's base URL. Blank means OpenRouter's, and is only valid with [AiProvider.OPENROUTER]. */
    val baseUrl: String = "",
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
     * Speech-to-text model slug for voice notes and audio files, sent to the provider's
     * `/audio/transcriptions` (as multipart on an OpenAI-compatible server). The transcript then runs
     * the normal text capture against [taskAssistantModel]. No fallback: blank declines audio capture.
     */
    val transcriptionModel: String = "",
    /** Settings that only apply with [AiProvider.OPENROUTER]. */
    val openrouter: OpenRouterAiProperties = OpenRouterAiProperties(),
    /** Settings that only apply with [AiProvider.OPENAI_COMPATIBLE]. */
    val openaiCompatible: OpenAiCompatibleAiProperties = OpenAiCompatibleAiProperties(),
    /** Per-functionality reasoning/effort configuration. */
    val reasoning: AiReasoningProperties = AiReasoningProperties(),
    /** Hard cap on how many accounts may hold a granted AI tier at once. */
    val tierCap: AiTierCapProperties = AiTierCapProperties(),
) {
    /** The base URL calls go to: [baseUrl], or OpenRouter's when blank on OpenRouter. */
    val apiBaseUrl: String
        get() = baseUrl.ifBlank { if (provider == AiProvider.OPENROUTER) OPENROUTER_BASE_URL else "" }

    /**
     * Whether the instance has an AI provider at all; without one, every AI feature is dark. OpenRouter
     * needs a key; an OpenAI-compatible server needs a URL, and may need no key (a local one).
     */
    val enabled: Boolean
        get() = when (provider) {
            AiProvider.OPENROUTER -> apiKey.isNotBlank()
            AiProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank()
        }

    /** Whether calls go out restricted to OpenRouter's zero-data-retention endpoints. */
    val zeroDataRetention: Boolean
        get() = provider == AiProvider.OPENROUTER && openrouter.zeroDataRetention

    /**
     * Whether user-facing copy may promise zero data retention: AI is on, and restricted to ZDR
     * endpoints. Never with an OpenAI-compatible server: whether it keeps prompts is the operator's
     * arrangement with it, which the app can neither request nor check.
     */
    val zeroDataRetentionInEffect: Boolean
        get() = enabled && zeroDataRetention

    /**
     * Distinct set of configured model slugs, for the startup capability prefetch and the
     * zero-data-retention check (both OpenRouter-only). OpenRouter lists speech-to-text models in both `/model/{slug}` and
     * `/endpoints/zdr`, so the transcription model is checked like any chat model.
     */
    val configuredModels: Set<String>
        get() = setOf(weeklyPlanningModel, taskAssistantModel, visionModel, transcriptionModel)
            .filter { it.isNotBlank() }
            .toSet()
}

enum class AiProvider {
    /** OpenRouter (https://openrouter.ai), the default: capabilities and zero data retention are checked through it. */
    OPENROUTER,

    /** Any server speaking the OpenAI chat-completions API: a provider's own endpoint, or self-hosted inference. */
    OPENAI_COMPATIBLE,
}

const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

data class OpenRouterAiProperties(
    /**
     * Route every LLM call only to provider endpoints with a zero-data-retention policy (OpenRouter's
     * `provider.zdr`). On by default, like the client library's own default: the privacy policy and
     * landing page promise it. A model with no
     * ZDR endpoint then fails every call ("no endpoints found") rather than quietly falling back to one
     * that keeps prompts — check https://openrouter.ai/api/v1/endpoints/zdr when picking models.
     */
    val zeroDataRetention: Boolean = true,
)

/**
 * What the models on an OpenAI-compatible server can do. OpenRouter reports this per model; such a
 * server doesn't, so the operator states it, and the app assumes nothing it isn't told.
 */
data class OpenAiCompatibleAiProperties(
    /** Whether the configured models accept `reasoning_effort`, so the [AiReasoningProperties] levels are sent. */
    val reasoning: Boolean = false,
    /** What the vision model reads besides text: `image` and/or `pdf`. Empty declines media captures. */
    val visionInputs: Set<String> = emptySet(),
)

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
