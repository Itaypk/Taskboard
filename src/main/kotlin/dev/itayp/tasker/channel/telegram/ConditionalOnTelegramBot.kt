package dev.itayp.tasker.channel.telegram

import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Registers the annotated bean only when the Telegram bot is switched on **and** has a token.
 *
 * `tasker.telegram.enabled` defaults to `true` under the `prod` profile so the hosted instance keeps
 * its bot without extra configuration, but a self-hosted instance that never set
 * `TASKER_TELEGRAM_BOT_TOKEN` must still start — it simply runs without the bot. Requiring both is
 * what lets one default serve both cases.
 *
 * Covers the bot (messaging, commands, quick-add) only. Telegram *login* is gated separately by the
 * OIDC client id/secret — see [dev.itayp.tasker.config.TelegramAuthProperties.loginConfigured].
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnTelegramBotCondition::class)
annotation class ConditionalOnTelegramBot

class OnTelegramBotCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome =
        if (isTelegramBotEnabled { context.environment.getProperty(it) }) {
            ConditionOutcome.match("tasker.telegram.enabled is true and a bot token is set")
        } else {
            ConditionOutcome.noMatch("tasker.telegram.enabled is false or tasker.telegram.bot-token is blank")
        }

    companion object {
        /** Shared with the startup summary so the log line and the bean wiring can't disagree. */
        fun isTelegramBotEnabled(property: (String) -> String?): Boolean =
            property("tasker.telegram.enabled").toBoolean() &&
                !property("tasker.telegram.bot-token").isNullOrBlank()
    }
}
