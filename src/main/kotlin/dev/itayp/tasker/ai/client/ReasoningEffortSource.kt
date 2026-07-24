package dev.itayp.tasker.ai.client

/**
 * Supplies the configured reasoning effort for a given conversation type, or null when none is
 * configured. This is the application-specific half of reasoning resolution — [ReasoningResolver]
 * keeps only the generic validation against a model's advertised capabilities. The app implements
 * this from its own per-functionality config (see `AiPropertiesReasoningEffortSource`).
 */
fun interface ReasoningEffortSource {
    fun effortFor(conversationType: String): String?
}
