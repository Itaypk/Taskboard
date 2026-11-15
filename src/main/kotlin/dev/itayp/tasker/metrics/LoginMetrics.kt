package dev.itayp.tasker.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

enum class LoginProvider { TELEGRAM, EMAIL, LOCAL, DEMO }

/** `link` is attaching a provider to a signed-in account; only Telegram has a link round-trip today. */
enum class LoginMode { LOGIN, LINK }

enum class LoginOutcome {
    SUCCESS,

    /** User-side or policy refusals: a cancelled or replayed round-trip, a stale link, closed registration. Noisy; don't alert. */
    REJECTED,

    /** The provider exchange or verification failed (e.g. Telegram's token endpoint or JWKS). The signal worth alerting on. */
    ERROR,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
}

/**
 * `tasker.auth.login{provider, mode, outcome}`: one count per sign-in attempt that reached a login
 * endpoint, the auth counterpart of `tasker.email.sent`. It exists because a provider can break
 * without notice (Telegram once switched its JWKS endpoint to gzip and every login failed), and a
 * WARN line per failure is invisible until someone complains.
 *
 * The combinations that can happen are registered at zero on startup. Without that, a counter's
 * first increment creates the series at 1, and `increase()` can't see a rise from a sample that
 * doesn't exist, so the first failure after a deploy would never fire an alert.
 */
@Component
class LoginMetrics(private val meterRegistry: MeterRegistry) {

    @PostConstruct
    fun bind() {
        EXPECTED.forEach { (provider, mode, outcome) -> counter(provider, mode, outcome) }
    }

    fun record(provider: LoginProvider, outcome: LoginOutcome, mode: LoginMode = LoginMode.LOGIN) {
        counter(provider, mode, outcome).increment()
    }

    private fun counter(provider: LoginProvider, mode: LoginMode, outcome: LoginOutcome): Counter =
        Counter.builder(NAME)
            .description("Sign-in attempts by provider, mode and outcome")
            .tag("provider", provider.name.lowercase())
            .tag("mode", mode.name.lowercase())
            .tag("outcome", outcome.name.lowercase())
            .register(meterRegistry)

    companion object {
        const val NAME = "tasker.auth.login"

        private val EXPECTED: List<Triple<LoginProvider, LoginMode, LoginOutcome>> = buildList {
            val telegram = listOf(LoginOutcome.SUCCESS, LoginOutcome.REJECTED, LoginOutcome.ERROR)
            LoginMode.entries.forEach { mode -> telegram.forEach { add(Triple(LoginProvider.TELEGRAM, mode, it)) } }
            listOf(LoginOutcome.SUCCESS, LoginOutcome.REJECTED)
                .forEach { add(Triple(LoginProvider.EMAIL, LoginMode.LOGIN, it)) }
            listOf(LoginOutcome.SUCCESS, LoginOutcome.INVALID_CREDENTIALS, LoginOutcome.RATE_LIMITED)
                .forEach { add(Triple(LoginProvider.LOCAL, LoginMode.LOGIN, it)) }
            listOf(LoginOutcome.SUCCESS, LoginOutcome.REJECTED)
                .forEach { add(Triple(LoginProvider.DEMO, LoginMode.LOGIN, it)) }
        }
    }
}
