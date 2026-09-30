package dev.itayp.tasker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/**
 * Self-hosters replace the icons (see TRADEMARKS.md) by putting a directory ahead of the bundled
 * static files — `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/branding/,classpath:/static/`, as
 * documented in docs/CONFIGURATION.md. Pins that the override wins and nothing else is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class BrandingAssetsOverrideIntegrationTest(@Autowired val rest: TestRestTemplate) {

    companion object {
        private val brandingDir = Files.createTempDirectory("branding").also {
            Files.writeString(it.resolve("apple-touch-icon.png"), "custom-icon")
        }

        @JvmStatic
        @DynamicPropertySource
        fun staticLocations(registry: DynamicPropertyRegistry) {
            registry.add("spring.web.resources.static-locations") { "file:$brandingDir/,classpath:/static/" }
        }
    }

    @Test
    fun `a file in the branding directory replaces the bundled one`() {
        val response = rest.getForEntity("/apple-touch-icon.png", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo("custom-icon")
    }
}
