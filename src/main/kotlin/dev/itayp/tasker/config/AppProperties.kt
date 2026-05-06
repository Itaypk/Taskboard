package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.app")
data class AppProperties(
    val baseUrl: String = "https://backlog.fyi",
)
