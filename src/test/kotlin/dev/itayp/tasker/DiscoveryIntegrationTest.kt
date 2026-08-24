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
 * The discovery surface for the external API: `/llms.txt`, `/.well-known/api-catalog`, the
 * sitemap entries, and the contract/skill files themselves. All must be reachable with **no**
 * credential of any kind — no session cookie, no bearer token.
 *
 * Every assertion checks the *body*, not just the status. `SpaErrorController` forwards unmatched
 * paths to `index.html`, so a misnamed path or a file that failed to get packaged would otherwise
 * masquerade as a page rather than an error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class DiscoveryIntegrationTest(
    @Autowired val rest: TestRestTemplate,
) {

    @Test
    fun `llms_txt is served anonymously and is the real file`() {
        val response = rest.getForEntity("/llms.txt", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).startsWith("# Backlog.fyi")
        // Guards against the SPA fallback quietly answering instead.
        assertThat(response.body).doesNotContain("<!doctype html")
        assertThat(response.body).contains("/external-api/SKILL.md", "/external-api/openapi.yaml")
    }

    @Test
    fun `api-catalog is a linkset naming the contract and the guide`() {
        val response = rest.getForEntity("/.well-known/api-catalog", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // RFC 9727 requires this exact media type; a static file could not produce it.
        assertThat(response.headers.contentType.toString()).startsWith("application/linkset+json")

        val body = response.body ?: ""
        assertThat(body).contains("\"linkset\"", "\"service-desc\"", "\"service-doc\"")
        assertThat(body).contains("/external-api/openapi.yaml", "/external-api/SKILL.md")
        assertThat(body).contains("/api/external/v1")
    }

    /**
     * The links must be absolute and must follow the configured base URL — the whole reason the
     * catalogue is generated rather than a static file. In tests the default base URL applies.
     */
    @Test
    fun `api-catalog links are absolute`() {
        val body = rest.getForEntity("/.well-known/api-catalog", String::class.java).body ?: ""

        assertThat(body).contains("https://backlog.fyi/external-api/openapi.yaml")
        assertThat(body).doesNotContain("\"href\":\"/")
    }

    @Test
    fun `the contract and the skill are served anonymously`() {
        val spec = rest.getForEntity("/external-api/openapi.yaml", String::class.java)
        assertThat(spec.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(spec.body).contains("openapi:", "/api/external/v1/tasks")

        val skill = rest.getForEntity("/external-api/SKILL.md", String::class.java)
        assertThat(skill.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(skill.body).contains("name: backlog-fyi")
    }

    @Test
    fun `sitemap advertises the contract and the skill`() {
        val response = rest.getForEntity("/sitemap.xml", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body ?: ""
        assertThat(body).contains("<loc>https://backlog.fyi/external-api/SKILL.md</loc>")
        assertThat(body).contains("<loc>https://backlog.fyi/external-api/openapi.yaml</loc>")
    }

    @Test
    fun `robots points at llms_txt`() {
        val body = rest.getForEntity("/robots.txt", String::class.java).body ?: ""

        assertThat(body).contains("Sitemap: https://backlog.fyi/sitemap.xml")
        assertThat(body).contains("https://backlog.fyi/llms.txt")
    }
}
