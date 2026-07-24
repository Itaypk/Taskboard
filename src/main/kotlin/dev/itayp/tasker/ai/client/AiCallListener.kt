package dev.itayp.tasker.ai.client

/**
 * Observer invoked by [AiClient] after each call resolves, for usage accounting / metrics.
 * Kept as a seam so the client core carries no dependency on the app's persistence or metrics;
 * the app wires `AiUsageTracker` as the implementation.
 *
 * Implementations must be best-effort — accounting must never break the AI call.
 */
interface AiCallListener {
    fun recordSuccess(context: AiCallContext, request: ChatRequest, response: ChatResponse)
    fun recordFailure(context: AiCallContext, request: ChatRequest)
}
