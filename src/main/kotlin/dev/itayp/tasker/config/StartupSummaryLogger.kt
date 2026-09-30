package dev.itayp.tasker.config

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.telegram.OnTelegramBotCondition
import dev.itayp.tasker.service.LocalLoginService
import dev.itayp.tasker.service.LoginMethods
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * One line at startup saying which optional integrations this instance runs with, plus a warning
 * for each gap an operator would otherwise only find by clicking around. Optional integrations
 * degrade rather than fail startup (see [ProductionConfigValidator] for the ones that do fail), so
 * this is where a self-hoster learns that, say, the bot never started because no token was set.
 *
 * Holds no secrets: only whether each value is present.
 */
@Component
class StartupSummaryLogger(
    private val appProperties: AppProperties,
    private val loginMethods: LoginMethods,
    private val localLoginService: LocalLoginService,
    private val emailProperties: EmailProperties,
    private val aiProperties: AiProperties,
    private val prometheusAuthProperties: PrometheusAuthProperties,
    private val environment: Environment,
) {
    private val log = LoggerFactory.getLogger(StartupSummaryLogger::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun logSummary() {
        val telegramBot = OnTelegramBotCondition.isTelegramBotEnabled { environment.getProperty(it) }
        val signIn = buildList {
            if (loginMethods.password) add("password (${localLoginService.userCount} user(s))")
            if (loginMethods.email) add("email")
            if (loginMethods.telegram) add("telegram")
            if (loginMethods.demo) add("demo")
        }
        log.info(
            "Instance ready at {}: sign-in=[{}], registration={}, email={}, telegramBot={}, ai={}, metrics={}",
            appProperties.baseUrl,
            signIn.joinToString(),
            if (loginMethods.registrationOpen) "open" else "closed",
            onOff(emailProperties.enabled),
            onOff(telegramBot),
            onOff(aiProperties.apiKey.isNotBlank()),
            onOff(prometheusAuthProperties.configured),
        )

        if (signIn.isEmpty()) {
            log.warn(
                "No sign-in method is available, so nobody can log in. Set TASKER_LOCAL_USERS, " +
                    "enable email (TASKER_EMAIL_ENABLED + SMTP settings) or configure Telegram login.",
            )
        }
        if (aiProperties.apiKey.isBlank()) {
            log.warn("TASKER_AI_API_KEY is not set: the weekly planning assistant and AI capture are unavailable.")
        }
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"
}
