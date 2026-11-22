package dev.itayp.tasker.metrics

import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.AiCallGate
import dev.itayp.nescioquid.openrouter.AiCallListener
import dev.itayp.nescioquid.openrouter.AiClientProperties
import dev.itayp.nescioquid.openrouter.AiRequest
import dev.itayp.nescioquid.openrouter.AiResponse
import dev.itayp.nescioquid.openrouter.ApiKeyClient
import dev.itayp.nescioquid.openrouter.OpenRouterTransport
import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.ai.AiProvider
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertContains

/**
 * Asserted on the Prometheus exposition rather than the registry, because the names an alert queries
 * are what comes out of the naming convention, and a base unit can double-suffix a name without
 * anything failing. No Spring context: the class needs only a registry
 * and the library's client, and a context with AI enabled would make real OpenRouter calls at startup.
 */
class OpenRouterKeyMetricsTest {

    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val metrics = OpenRouterKeyMetrics(registry, ApiKeyClient(transport()), AiProperties(apiKey = "k"))

    @Test
    fun `exposes the limit, remainder and usage under the names the alert queries`() {
        respond("""{"data":{"limit":10,"limit_remaining":0.75,"limit_reset":"monthly","usage":42.5,"usage_daily":0.5,"usage_weekly":2,"usage_monthly":9.25}}""")

        metrics.refresh()

        val scrape = registry.scrape()
        assertContains(scrape, "tasker_openrouter_key_limit_usd 10.0")
        assertContains(scrape, "tasker_openrouter_key_limit_remaining_usd 0.75")
        assertContains(scrape, """tasker_openrouter_key_usage_usd{period="total"} 42.5""")
        assertContains(scrape, """tasker_openrouter_key_usage_usd{period="month"} 9.25""")
    }

    @Test
    fun `a key with no cap exposes usage and a NaN limit`() {
        respond("""{"data":{"limit":null,"limit_remaining":null,"usage":3.5}}""")

        metrics.refresh()

        val scrape = registry.scrape()
        assertContains(scrape, "tasker_openrouter_key_limit_usd NaN")
        assertContains(scrape, "tasker_openrouter_key_limit_remaining_usd NaN")
        assertContains(scrape, """tasker_openrouter_key_usage_usd{period="total"} 3.5""")
    }

    @Test
    fun `a failed poll clears the previous readings rather than keeping them`() {
        respond("""{"data":{"limit":10,"limit_remaining":5,"usage":5}}""")
        server.expect(requestTo("https://openrouter.ai/api/v1/key")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        metrics.refresh()
        metrics.refresh()

        val scrape = registry.scrape()
        assertContains(scrape, "tasker_openrouter_key_limit_remaining_usd NaN")
        assertContains(scrape, """tasker_openrouter_key_usage_usd{period="total"} NaN""")
    }

    @Test
    fun `counts failures in a row and resets on success`() {
        val key = "https://openrouter.ai/api/v1/key"
        server.expect(requestTo(key)).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo(key)).andRespond(withStatus(HttpStatus.FORBIDDEN))
        respond("""{"data":{"limit":10,"limit_remaining":5,"usage":5}}""")

        metrics.refresh()
        metrics.refresh()
        assertContains(registry.scrape(), "tasker_openrouter_key_poll_consecutive_failures 2.0")

        metrics.refresh()
        assertContains(registry.scrape(), "tasker_openrouter_key_poll_consecutive_failures 0.0")
    }

    @Test
    fun `an instance without an AI key never polls and counts no failures`() {
        val dark = OpenRouterKeyMetrics(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), ApiKeyClient(transport()), AiProperties())

        dark.refresh()

        server.verify() // no request expected, so any call would fail here
    }

    @Test
    fun `an instance on another provider never polls`() {
        val properties = AiProperties(provider = AiProvider.OPENAI_COMPATIBLE, baseUrl = "http://localhost:11434/v1")
        val other = OpenRouterKeyMetrics(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), ApiKeyClient(transport()), properties)

        other.refresh()

        server.verify()
    }

    private fun respond(body: String) {
        server.expect(requestTo("https://openrouter.ai/api/v1/key")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun transport(): OpenRouterTransport {
        val properties = AiClientProperties(apiKey = "k", baseUrl = "https://openrouter.ai/api/v1", configuredModels = emptySet())
        val noGate = AiCallGate { _, _ -> }
        val noListener = object : AiCallListener {
            override fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse) = Unit
            override fun recordFailure(context: AiCallContext, request: AiRequest) = Unit
        }
        return OpenRouterTransport(properties, noGate, noListener, builder)
    }
}
