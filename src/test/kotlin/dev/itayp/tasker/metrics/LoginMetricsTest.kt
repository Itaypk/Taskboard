package dev.itayp.tasker.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LoginMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = LoginMetrics(registry).also { it.bind() }

    private fun count(provider: String, mode: String, outcome: String): Double? =
        registry.find(LoginMetrics.NAME).tags("provider", provider, "mode", mode, "outcome", outcome).counter()?.count()

    @Test
    fun `the Telegram error series exists at zero before any login, so the first failure is visible to increase()`() {
        assertThat(count("telegram", "login", "error")).isEqualTo(0.0)
        assertThat(count("telegram", "link", "error")).isEqualTo(0.0)
    }

    @Test
    fun `impossible combinations are not pre-registered`() {
        assertThat(count("demo", "link", "success")).isNull()
        assertThat(count("email", "login", "invalid_credentials")).isNull()
    }

    @Test
    fun `record increments the tagged counter, defaulting to login mode`() {
        metrics.record(LoginProvider.TELEGRAM, LoginOutcome.ERROR)
        metrics.record(LoginProvider.TELEGRAM, LoginOutcome.SUCCESS, LoginMode.LINK)

        assertThat(count("telegram", "login", "error")).isEqualTo(1.0)
        assertThat(count("telegram", "link", "success")).isEqualTo(1.0)
        assertThat(count("telegram", "link", "error")).isEqualTo(0.0)
    }
}
