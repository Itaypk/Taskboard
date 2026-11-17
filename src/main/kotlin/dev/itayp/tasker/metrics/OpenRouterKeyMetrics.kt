package dev.itayp.tasker.metrics

import dev.itayp.nescioquid.openrouter.ApiKeyClient
import dev.itayp.nescioquid.openrouter.ApiKeyInfo
import dev.itayp.tasker.ai.AiProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The OpenRouter key's spending limit and usage, as gauges, so an alert can warn before the credit
 * runs out (or the key is revoked) rather than after every AI feature starts failing.
 *
 * - `tasker_openrouter_key_limit_usd` — the key's cap. NaN when the key has none.
 * - `tasker_openrouter_key_limit_remaining_usd` — what's left of it this window. NaN likewise.
 * - `tasker_openrouter_key_usage_usd{period=total|day|week|month}` — spend, for a dashboard.
 * - `tasker_openrouter_key_poll_consecutive_failures` — polls failed in a row, back to 0 on success.
 *
 * Same names, after the prefix, as the sibling Reforge app's, so one alert rule ports to the other.
 *
 * "How close to the cap" is `remaining / limit`, never `usage / limit`: with a limit that resets
 * monthly, all-time usage keeps climbing while the limit refills.
 *
 * **Polled, not read per scrape**, like [UsageMetrics]: a scrape-time read would be an outbound call on
 * every scrape, and one slow OpenRouter answer would stall the whole endpoint. Fifteen minutes is far
 * finer than credit moves at this scale, and `GET /key` is free and counts toward no rate limit.
 *
 * **A failed poll clears the readings** to NaN rather than keeping the last good ones, so a stale
 * balance never reads as current. Every PromQL comparison against NaN is false, so a credit alert
 * stays quiet on a missing reading; the failure run is what alerts instead, on a revoked or disabled
 * key or an OpenRouter that keeps refusing. A run rather than a counter, because "several in a row"
 * is the signal — one blip a day is not — and a counter's `increase()` can't say that.
 *
 * On an instance without an AI key there is nothing to poll: the gauges stay NaN and the failure run
 * at 0, rather than counting a self-hoster's deliberate choice as an outage.
 */
@Component
class OpenRouterKeyMetrics(
    registry: MeterRegistry,
    private val keys: ApiKeyClient,
    private val aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(OpenRouterKeyMetrics::class.java)

    @Volatile
    private var latest: ApiKeyInfo? = null

    private val consecutiveFailures = AtomicInteger()

    init {
        gauge(registry, "tasker.openrouter.key.limit.usd", "The OpenRouter key's spending limit") { it.limit }
        gauge(registry, "tasker.openrouter.key.limit.remaining.usd", "What is left of the OpenRouter key's limit this window") {
            it.limitRemaining
        }
        gauge(registry, "tasker.openrouter.key.usage.usd", "OpenRouter key spend", "total") { it.usage }
        gauge(registry, "tasker.openrouter.key.usage.usd", "OpenRouter key spend", "day") { it.usageDaily }
        gauge(registry, "tasker.openrouter.key.usage.usd", "OpenRouter key spend", "week") { it.usageWeekly }
        gauge(registry, "tasker.openrouter.key.usage.usd", "OpenRouter key spend", "month") { it.usageMonthly }
        Gauge.builder("tasker.openrouter.key.poll.consecutive.failures") { consecutiveFailures.get() }
            .description("Polls of the OpenRouter key's usage that failed in a row")
            .register(registry)
    }

    // First run fires as soon as the scheduler starts, so the gauges have values within moments of
    // a deploy rather than fifteen minutes after it.
    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.MINUTES)
    fun refresh() {
        if (!aiProperties.enabled) return
        latest = try {
            keys.currentKey().also { consecutiveFailures.set(0) }
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            log.warn("Couldn't read the OpenRouter key's usage and limit ({} in a row): {}", failures, e.message)
            null
        }
    }

    /**
     * NaN for "no reading" and for "no cap" alike. The Prometheus registry exposes it as a literal
     * `NaN` sample rather than dropping the series, which is harmless: an alert only compares values.
     */
    private fun gauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        period: String? = null,
        value: (ApiKeyInfo) -> Double?,
    ) {
        val builder = Gauge.builder(name) { latest?.let(value) ?: Double.NaN }
            .description(description)
            .baseUnit("usd")
        if (period != null) builder.tag("period", period)
        builder.register(registry)
    }
}
