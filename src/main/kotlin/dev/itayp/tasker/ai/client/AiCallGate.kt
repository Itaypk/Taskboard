package dev.itayp.tasker.ai.client

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Hook invoked immediately before the outbound OpenRouter call, in the same place a future
 * per-user rate limiter will live. A gate may throw to refuse the call (the exception propagates
 * to the caller); the default gate is a no-op.
 *
 * Kept as a [fun interface] so a real limiter can be dropped in as a single bean; defining any
 * `AiCallGate` bean replaces [noOpAiCallGate] below.
 */
fun interface AiCallGate {
    fun beforeCall(context: AiCallContext, request: ChatRequest)
}

@Configuration
class AiCallGateConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiCallGate::class)
    fun noOpAiCallGate(): AiCallGate = AiCallGate { _, _ -> }
}
