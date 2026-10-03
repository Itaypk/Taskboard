package dev.itayp.tasker.service

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.config.TelegramAuthProperties
import org.springframework.stereotype.Component

/**
 * Which sign-in methods this instance actually offers, derived from configuration. One source for
 * the login page (via `GET /api/public/config`) and the startup summary, so neither can advertise a
 * method the other says is off.
 */
@Component
class LoginMethods(
    private val telegramAuthProperties: TelegramAuthProperties,
    private val emailProperties: EmailProperties,
    private val localLoginService: LocalLoginService,
    private val registrationPolicy: RegistrationPolicy,
) {
    val telegram: Boolean get() = telegramAuthProperties.loginConfigured

    /** Magic links need a real sender; with email disabled the link would only reach a log line. */
    val email: Boolean get() = emailProperties.enabled

    val password: Boolean get() = localLoginService.enabled

    val demo: Boolean get() = registrationPolicy.demoAvailable

    val registrationOpen: Boolean get() = registrationPolicy.open
}
