package dev.itayp.tasker.ai.usage

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.AiCallListener
import dev.itayp.nescioquid.openrouter.AiRequest
import dev.itayp.nescioquid.openrouter.AiResponse
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.ModelCapabilityService
import dev.itayp.nescioquid.openrouter.Usage
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Records AI usage for every [dev.itayp.nescioquid.openrouter.AiClient.chat] call: a durable per-call
 * row (per-user accounting, future rate-limit input), aggregate Prometheus counters, and a debug
 * log line. Tracking is best-effort — a tracking failure must never break the AI call — so all
 * persistence is wrapped and swallowed here.
 *
 * Metrics intentionally carry no per-user tag (cardinality); per-user breakdowns come from the table.
 */
@Component
class AiUsageTracker(
    private val repository: AiUsageEventRepository,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val modelCapabilityService: ModelCapabilityService,
) : AiCallListener {
    private val log = LoggerFactory.getLogger(AiUsageTracker::class.java)

    /**
     * Effort level to tag the call with. The explicit request effort wins; otherwise, for a
     * reasoning model called without an explicit level, it's the model's default effort; for a
     * non-reasoning model it's "none". [DEFAULT_EFFORT_UNKNOWN] covers a reasoning model that didn't
     * advertise a default. Non-chat requests (e.g. image generation) have no reasoning concept, so
     * they're tagged [EFFORT_NONE] too.
     */
    private fun effortLabel(request: AiRequest): String {
        val chatRequest = request as? ChatRequest ?: return EFFORT_NONE
        chatRequest.reasoning?.effort?.let { return it }
        val capabilities = modelCapabilityService.get(chatRequest.model)
        return when {
            capabilities == null || !capabilities.supportsReasoning -> EFFORT_NONE
            else -> capabilities.defaultEffort ?: DEFAULT_EFFORT_UNKNOWN
        }
    }

    override fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse) {
        // Token accounting only applies to the chat/image usage shape; transcription bills by audio
        // duration (TranscriptionUsage) and has no token fields to report here.
        val usage = response.usage as? Usage
        // OpenRouter echoes the resolved model/provider; fall back to the requested model.
        val model = response.model ?: request.model
        record(
            context = context,
            model = model,
            provider = response.provider,
            effort = effortLabel(request),
            status = AiUsageStatus.SUCCESS,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens
                ?: usage?.let { it.promptTokens + it.completionTokens },
            cachedTokens = usage?.promptTokensDetails?.cachedTokens,
            cacheWriteTokens = usage?.promptTokensDetails?.cacheWriteTokens,
        )
    }

    override fun recordFailure(context: AiCallContext, request: AiRequest) {
        // No usage body on failure — record the attempt so failed calls are still accounted for.
        record(
            context = context,
            model = request.model,
            provider = null,
            effort = effortLabel(request),
            status = AiUsageStatus.ERROR,
            promptTokens = null,
            completionTokens = null,
            totalTokens = null,
            cachedTokens = null,
            cacheWriteTokens = null,
        )
    }

    private fun record(
        context: AiCallContext,
        model: String,
        provider: String?,
        effort: String,
        status: AiUsageStatus,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        cachedTokens: Int?,
        cacheWriteTokens: Int?,
    ) {
        val providerTag = provider ?: "unknown"
        meterRegistry.counter(
            "tasker.ai.requests",
            "conversation_type", context.conversationType,
            "model", model,
            "provider", providerTag,
            "effort", effort,
            "outcome", status.name.lowercase(),
        ).increment()
        if (promptTokens != null) {
            meterRegistry.counter(
                "tasker.ai.tokens",
                "conversation_type", context.conversationType,
                "model", model,
                "provider", providerTag,
                "effort", effort,
                "type", "prompt",
            ).increment(promptTokens.toDouble())
        }
        if (completionTokens != null) {
            meterRegistry.counter(
                "tasker.ai.tokens",
                "conversation_type", context.conversationType,
                "model", model,
                "provider", providerTag,
                "effort", effort,
                "type", "completion",
            ).increment(completionTokens.toDouble())
        }
        // Prompt-caching breakdown, only present when the provider reports prompt_tokens_details.
        // Both are subsets/relatives of the prompt tokens (cache reads and cache writes), so they
        // are their own `type` series rather than added to the prompt/completion totals.
        if (cachedTokens != null) {
            meterRegistry.counter(
                "tasker.ai.tokens",
                "conversation_type", context.conversationType,
                "model", model,
                "provider", providerTag,
                "effort", effort,
                "type", "cached",
            ).increment(cachedTokens.toDouble())
        }
        if (cacheWriteTokens != null) {
            meterRegistry.counter(
                "tasker.ai.tokens",
                "conversation_type", context.conversationType,
                "model", model,
                "provider", providerTag,
                "effort", effort,
                "type", "cache_write",
            ).increment(cacheWriteTokens.toDouble())
        }

        log.debug(
            "AI usage user={} type={} status={} model={} provider={} effort={} promptTokens={} completionTokens={} cachedTokens={} cacheWriteTokens={} session={} conversation={}",
            context.userId, context.conversationType, status, model, providerTag, effort,
            promptTokens, completionTokens, cachedTokens, cacheWriteTokens,
            context.sessionId, context.conversationId,
        )

        persist(context, model, provider, status, promptTokens, completionTokens, totalTokens)
    }

    private fun persist(
        context: AiCallContext,
        model: String,
        provider: String?,
        status: AiUsageStatus,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
    ) {
        try {
            repository.save(AiUsageEventEntity().apply {
                this.userId = UUID.fromString(context.userId)
                this.conversationType = context.conversationType
                this.sessionId = context.sessionId?.let { UUID.fromString(it) }
                this.conversationId = context.conversationId?.let { UUID.fromString(it) }
                this.model = model
                this.provider = provider
                this.promptTokens = promptTokens
                this.completionTokens = completionTokens
                this.totalTokens = totalTokens
                this.status = status
                this.createdAt = clock.instant()
            })
        } catch (e: Exception) {
            // Accounting must not break the AI flow; the metric + log still captured the call.
            log.error("Failed to persist AI usage event for user {}", context.userId, e)
        }
    }

    companion object {
        // effort label for a non-reasoning model.
        private const val EFFORT_NONE = "none"
        // effort label for a reasoning model that didn't advertise a default effort.
        private const val DEFAULT_EFFORT_UNKNOWN = "default"
    }
}
