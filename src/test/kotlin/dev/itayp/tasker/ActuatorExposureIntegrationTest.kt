package dev.itayp.tasker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * Locks down the Actuator surface. Only `health` and `prometheus` are exposed
 * (`management.endpoints.web.exposure.include`); everything else must be unreachable, and the two
 * that are exposed must enforce their intended access rules:
 *   - `/actuator/health` is public but withholds component details from anonymous callers.
 *   - `/actuator/prometheus` is gated behind HTTP Basic (its own stateless filter chain).
 *
 * A misconfiguration here (e.g. flipping exposure to `*`) is a classic info-leak finding — exactly
 * the kind of thing an external scan flags — so it's worth pinning with a test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class ActuatorExposureIntegrationTest(@Autowired val rest: TestRestTemplate) {

    @Test
    fun `health is public but hides component details from anonymous callers`() {
        val response = rest.getForEntity("/actuator/health", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
        // show-details=when-authorized: anonymous callers must not see the per-component breakdown
        // (db connectivity, disk space, etc.).
        assertThat(response.body).doesNotContain("\"components\"")
        assertThat(response.body).doesNotContain("diskSpace")
    }

    @Test
    fun `prometheus metrics require basic auth`() {
        val response = rest.getForEntity("/actuator/prometheus", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `prometheus metrics are served with correct basic auth`() {
        // Dev defaults from PrometheusAuthProperties.
        val response = rest.withBasicAuth("prometheus", "prometheus-dev")
            .getForEntity("/actuator/prometheus", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `prometheus metrics reject wrong basic auth`() {
        val response = rest.withBasicAuth("prometheus", "wrong-password")
            .getForEntity("/actuator/prometheus", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    /**
     * Sensitive/unexposed endpoints must not be mapped at all (404 from no handler — they fall under
     * the catch-all `permitAll`, so there's no auth challenge to hide behind). `heapdump`/`env` would
     * be the most damaging leaks.
     */
    @ParameterizedTest
    @ValueSource(strings = ["env", "beans", "mappings", "configprops", "loggers", "threaddump", "heapdump", "metrics", "info", "scheduledtasks", "caches", "shutdown"])
    fun `non-allowlisted actuator endpoints are not exposed`(endpoint: String) {
        val response = rest.getForEntity("/actuator/$endpoint", String::class.java)
        assertThat(response.statusCode)
            .withFailMessage("/actuator/$endpoint should be unmapped (404) but returned ${response.statusCode}")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }
}
