package dev.itayp.tasker

import dev.itayp.tasker.controller.ContentPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * A renamed instance gets its own name and URL in the HTML itself — title, meta/OG tags, the no-JS
 * fallback, the inline config the SPA reads — on every way into the SPA, and in the web manifest. The
 * content pages carry their own text, title and canonical URL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "tasker.app.name=Acme Tasks",
        "tasker.app.base-url=https://tasks.acme.test",
        "tasker.app.support-email=help@acme.test",
        "tasker.app.abuse-email=abuse@acme.test",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class IndexHtmlIntegrationTest(@Autowired val rest: TestRestTemplate) {

    private fun getHtml(path: String) = rest.exchange(
        path, HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { accept = listOf(MediaType.TEXT_HTML) }),
        String::class.java,
    )

    private fun assertBranded(body: String?) {
        assertBrandedDocument(body)
        assertThat(body).contains(
            "<title>Acme Tasks - your personal tasks planner</title>",
            """<link rel="canonical" href="https://tasks.acme.test/" />""",
            """<div id="root"></div>""",
            """<div id="prerendered-landing" hidden>""",
        )
    }

    /** What every variant of the document carries, content pages included. */
    private fun assertBrandedDocument(body: String?) {
        assertThat(body).contains(
            """content="https://tasks.acme.test/og-image.png"""",
            """"name": "Acme Tasks",""",
            """<script id="app-config" type="application/json">{""",
            """"branding":{"name":"Acme Tasks"""",
        )
        assertThat(body).doesNotContain("Backlog.fyi", "backlog.fyi", "{{")
    }

    @Test
    fun `the root document carries the instance's name, URL and inline config`() {
        val response = getHtml("/")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType?.isCompatibleWith(MediaType.TEXT_HTML)).isTrue()
        assertBranded(response.body)
    }

    @Test
    fun `client routes get the same rendered document`() {
        val response = getHtml("/settings")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertBranded(response.body)
    }

    @ParameterizedTest
    @EnumSource(ContentPage::class)
    fun `a content page carries its own text, title and canonical URL`(page: ContentPage) {
        val response = getHtml(page.path)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body
        assertBrandedDocument(body)
        assertThat(body).contains(
            "<title>${page.title} — Acme Tasks</title>",
            """<link rel="canonical" href="https://tasks.acme.test${page.path}" />""",
            """<meta property="og:url"         content="https://tasks.acme.test${page.path}" />""",
            """<div id="root"><main class="board-wrap"><article class="content-page"><header><h1>${page.title}</h1>""",
            "Acme Tasks",
        )
        // Rendered markdown, not the landing fallback or the home page's head.
        assertThat(body).contains("<h2>").doesNotContain("prerendered-landing", "your personal tasks planner</title>")
    }

    @Test
    fun `content pages carry the instance's contact addresses`() {
        assertThat(getHtml("/privacy").body).contains(
            """<a href="mailto:help@acme.test">help@acme.test</a>""",
            """<a href="mailto:abuse@acme.test">abuse@acme.test</a>""",
        )
        assertThat(getHtml("/faq").body).contains("https://tasks.acme.test/external-api/openapi.yaml")
    }

    @Test
    fun `each content page has its own ETag`() {
        assertThat(getHtml("/terms").headers.eTag)
            .isNotBlank()
            .isNotEqualTo(getHtml("/privacy").headers.eTag)
            .isNotEqualTo(getHtml("/").headers.eTag)
    }

    @Test
    fun `an unknown path stays a 404 but still renders the SPA`() {
        val response = getHtml("/no-such-page")
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertBranded(response.body)
    }

    @Test
    fun `a repeat request with the ETag is answered 304`() {
        val etag = getHtml("/").headers.eTag
        assertThat(etag).isNotBlank()
        val repeat = rest.exchange(
            "/", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { ifNoneMatch = listOf(etag!!) }),
            String::class.java,
        )
        assertThat(repeat.statusCode).isEqualTo(HttpStatus.NOT_MODIFIED)
    }

    @Test
    fun `the web manifest uses the instance's name`() {
        val response = rest.getForEntity("/manifest.json", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).startsWith("application/manifest+json")
        assertThat(response.body).contains(""""name":"Acme Tasks"""", """"short_name":"Acme Tasks"""", """"start_url":"/"""")
    }
}
