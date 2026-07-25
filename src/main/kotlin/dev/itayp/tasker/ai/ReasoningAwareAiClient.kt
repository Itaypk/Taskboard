package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.AiClient
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.ChatResponse
import org.springframework.stereotype.Component

/**
 * The application's entry point for LLM calls. Wraps the library [AiClient] and applies the app's
 * per-functionality reasoning policy: when a caller hasn't set [ChatRequest.reasoning] explicitly,
 * it's resolved from the call's conversation type (via [ReasoningResolver]) before the request is
 * sent. The library itself applies no reasoning — `ChatRequest` is the source of truth — so this is
 * where that decision lives now.
 *
 * All planning/agent call sites inject this rather than [AiClient] directly, so the policy applies
 * uniformly and new call sites get it for free.
 */
@Component
class ReasoningAwareAiClient(
    private val aiClient: AiClient,
    private val reasoningResolver: ReasoningResolver,
) {
    fun chat(request: ChatRequest, context: AiCallContext): ChatResponse {
        val effectiveRequest =
            if (request.reasoning == null) {
                request.copy(reasoning = reasoningResolver.resolve(context.conversationType, request.model))
            } else {
                request
            }
        return aiClient.chat(effectiveRequest, context)
    }
}
