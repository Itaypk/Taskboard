package dev.itayp.tasker.ai

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.ai")
data class AiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
)
