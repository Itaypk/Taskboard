package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Basic Auth credentials for `/actuator/prometheus`. The base config ships dev defaults; the `prod`
 * profile has none, and leaving both blank there closes the endpoint rather than failing startup —
 * a self-hosted instance that doesn't scrape metrics shouldn't have to invent credentials.
 */
@ConfigurationProperties("tasker.prometheus")
data class PrometheusAuthProperties(
    val username: String = "prometheus",
    val password: String = "prometheus-dev",
) {
    val configured: Boolean get() = username.isNotBlank() && password.isNotBlank()
}
