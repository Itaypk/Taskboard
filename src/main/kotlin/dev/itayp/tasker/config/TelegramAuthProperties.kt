package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.telegram")
data class TelegramAuthProperties(
    val botToken: String = "",
    val botUsername: String = "",
    val enabled: Boolean = false,
)
