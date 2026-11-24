package dev.itayp.tasker.config

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.ai.AiProvider
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.whatsapp.WhatsAppProperties
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
    private val inboundMailProperties: InboundMailProperties,
    private val whatsAppProperties: WhatsAppProperties,
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
            if (loginMethods.google) add("google")
            if (loginMethods.demo) add("demo")
        }
        log.info(
            "Instance ready at {}: sign-in=[{}], registration={}, email={}, telegramBot={}, ai={}, inboundMail={}, whatsApp={}, metrics={}",
            appProperties.baseUrl,
            signIn.joinToString(),
            if (loginMethods.registrationOpen) "open" else "closed",
            onOff(emailProperties.enabled),
            onOff(telegramBot),
            if (aiProperties.enabled) aiProperties.provider.name.lowercase().replace('_', '-') else "off",
            onOff(inboundMailProperties.active),
            onOff(whatsAppProperties.active),
            onOff(prometheusAuthProperties.configured),
        )

        if (signIn.isEmpty()) {
            log.warn(
                "No sign-in method is available, so nobody can log in. Set TASKER_LOCAL_USERS, " +
                    "enable email (TASKER_EMAIL_ENABLED + SMTP settings) or configure Telegram or Google login.",
            )
        }
        if (inboundMailProperties.enabled && !inboundMailProperties.active) {
            log.warn(
                "TASKER_INBOUND_MAIL_ENABLED is set, but TASKER_INBOUND_MAIL_DOMAIN or TASKER_INBOUND_MAIL_DELIVERY_TOKEN " +
                    "(at least ${InboundMailProperties.MIN_DELIVERY_TOKEN_LENGTH} characters) is not: inbound email stays off.",
            )
        }
        if (whatsAppProperties.enabled && !whatsAppProperties.active) {
            log.warn("TASKER_WHATSAPP_ENABLED is set, but TASKER_WHATSAPP_VERIFY_TOKEN or TASKER_WHATSAPP_APP_SECRET is not: the WhatsApp webhook stays off.")
        }
        if (whatsAppProperties.active && whatsAppProperties.logPayloads) {
            log.warn("TASKER_WHATSAPP_LOG_PAYLOADS is on: WhatsApp webhook bodies, including phone numbers and message text, are logged. Turn it off once testing is done.")
        }
        warnAboutAiConfiguration()
        if (environment.containsProperty(LEGACY_MULTIMODAL_MODEL)) {
            log.warn(
                "$LEGACY_MULTIMODAL_MODEL is deprecated and only sets TASKER_AI_VISION_MODEL now; voice notes need " +
                    "TASKER_AI_TRANSCRIPTION_MODEL. Rename it to TASKER_AI_VISION_MODEL: it will be dropped in a later release.",
            )
        }
    }

    private fun warnAboutAiConfiguration() {
        if (!aiProperties.enabled) {
            val missing = if (aiProperties.provider == AiProvider.OPENROUTER) "TASKER_AI_API_KEY" else "TASKER_AI_BASE_URL"
            log.warn("$missing is not set: the weekly planning assistant and AI capture are unavailable.")
        }
        if (environment.containsProperty(LEGACY_ZERO_DATA_RETENTION)) {
            log.warn(
                "$LEGACY_ZERO_DATA_RETENTION is no longer read: it only ever applied to OpenRouter, and is now " +
                    "TASKER_AI_OPENROUTER_ZERO_DATA_RETENTION (default true).",
            )
        }
        if (aiProperties.provider != AiProvider.OPENAI_COMPATIBLE || !aiProperties.enabled) return
        if (!environment.containsProperty(PLANNING_MODEL)) {
            log.warn(
                "TASKER_AI_PROVIDER is openai-compatible but $PLANNING_MODEL is not set, so the default OpenRouter " +
                    "model slug is sent to {}. Set it to a model that server serves.",
                aiProperties.apiBaseUrl,
            )
        }
        log.info(
            "AI calls go to an OpenAI-compatible server at {}. Zero data retention isn't requested or checked there: " +
                "whether prompts are kept is up to that server's operator.",
            aiProperties.apiBaseUrl,
        )
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private companion object {
        /** Replaced by TASKER_AI_VISION_MODEL and TASKER_AI_TRANSCRIPTION_MODEL (GitHub issue #362). */
        const val LEGACY_MULTIMODAL_MODEL = "TASKER_AI_MULTIMODAL_MODEL"

        /** Renamed to TASKER_AI_OPENROUTER_ZERO_DATA_RETENTION (GitHub issue #331). */
        const val LEGACY_ZERO_DATA_RETENTION = "TASKER_AI_ZERO_DATA_RETENTION"

        const val PLANNING_MODEL = "TASKER_AI_PLANNING_MODEL"
    }
}
