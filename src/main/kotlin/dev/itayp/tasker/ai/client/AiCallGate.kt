package dev.itayp.tasker.ai.client

import dev.itayp.tasker.ai.access.AiAccessService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Hook invoked immediately before the outbound OpenRouter call. A gate may throw to refuse
 * the call (the exception propagates to the caller).
 *
 * Wired by default to [AiAccessService]: refuses calls when the caller has opted out of AI
 * (binary toggle, also vetoed when a co-member of a shared board has opted out) and when the
 * caller has burned through their subscription tier's rolling-window token budget.
 *
 * Kept as a [fun interface] so tests can replace the gate with a no-op or a mock.
 */
fun interface AiCallGate {
    fun beforeCall(context: AiCallContext, request: ChatRequest)
}

@Configuration
class AiCallGateConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiCallGate::class)
    fun defaultAiCallGate(aiAccessService: AiAccessService): AiCallGate =
        AiCallGate { context, _ ->
            aiAccessService.requireAiEnabledForUser(context.userId)
            aiAccessService.requireWithinTierLimit(context.userId)
        }
}
