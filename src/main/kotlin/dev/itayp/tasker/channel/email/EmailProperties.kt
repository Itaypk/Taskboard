package dev.itayp.tasker.channel.email

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.email")
data class EmailProperties(
    val enabled: Boolean = false,
    val from: String = "",
    val fromName: String = "Tasker",
    val smtp: SmtpConfig = SmtpConfig(),
) {
    data class SmtpConfig(
        val host: String = "smtp.protonmail.ch",
        val port: Int = 587,
        val username: String = "",
        val password: String = "",
    )
}
