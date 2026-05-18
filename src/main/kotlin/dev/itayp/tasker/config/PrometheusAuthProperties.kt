package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.prometheus")
data class PrometheusAuthProperties(
    val username: String,
    val password: String,
)
