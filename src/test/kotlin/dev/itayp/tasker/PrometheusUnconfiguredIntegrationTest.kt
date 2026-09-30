package dev.itayp.tasker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * With no scrape credentials configured (the `prod` default for a self-hosted instance), the
 * metrics endpoint is closed outright — no login, the old dev one included, gets through.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["tasker.prometheus.username=", "tasker.prometheus.password="],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class PrometheusUnconfiguredIntegrationTest(@Autowired val rest: TestRestTemplate) {

    @Test
    fun `prometheus is closed when no credentials are configured`() {
        assertThat(rest.getForEntity("/actuator/prometheus", String::class.java).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(
            rest.withBasicAuth("prometheus", "prometheus-dev")
                .getForEntity("/actuator/prometheus", String::class.java).statusCode,
        ).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }
}
