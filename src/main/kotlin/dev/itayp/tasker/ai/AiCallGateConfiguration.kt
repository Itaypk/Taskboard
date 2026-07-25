package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallGate
import dev.itayp.tasker.ai.access.AiAccessService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the app's [AiCallGate] — the `openrouter-client` library ships no default gate, so the
 * app must supply one. This backs it with [AiAccessService]: refuses calls when the caller has
 * opted out of AI (binary toggle, also vetoed when a co-member of a shared board has opted out)
 * and when the caller has burned through their subscription tier's rolling-window token budget.
 *
 * [ConditionalOnMissingBean] lets tests replace the gate with a no-op or a mock.
 */
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
