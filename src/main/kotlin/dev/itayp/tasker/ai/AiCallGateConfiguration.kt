package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.AiCallGate
import dev.itayp.tasker.ai.access.AiAccessService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Provides the app's [AiCallGate] — the `openrouter-client` library ships no default gate, so the
 * app must supply one. This backs it with [AiAccessService]: refuses calls when the caller has
 * opted out of AI (binary toggle, also vetoed when a co-member of a shared board has opted out),
 * when their tier hasn't been granted access at all, and when the caller has burned through their
 * subscription tier's rolling-window token budget.
 *
 * [ConditionalOnMissingBean] lets tests replace the gate with a no-op or a mock.
 */
@Configuration
class AiCallGateConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiCallGate::class)
    fun defaultAiCallGate(aiAccessService: AiAccessService): AiCallGate =
        AiCallGate { context, _ ->
            val userId = UUID.fromString(context.userId)
            aiAccessService.requireAiEnabledForUser(userId)
            aiAccessService.requireAiTierGranted(userId)
            aiAccessService.requireWithinTierLimit(userId)
        }
}
