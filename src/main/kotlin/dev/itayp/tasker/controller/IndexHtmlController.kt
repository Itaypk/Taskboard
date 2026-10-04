package dev.itayp.tasker.controller

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Helper
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.service.PublicConfigProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.context.request.ServletWebRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Serves the SPA's `index.html` and `manifest.json` with this instance's name and URL filled in, so
 * the document title, meta/OG tags, the no-JS fallback and the installed-app name are right in the
 * HTML itself — for crawlers and link previews, not just after JavaScript runs — and the SPA reads its
 * configuration from an inline JSON block instead of fetching it on the critical path.
 *
 * Every way into the SPA ends here: `/` (this mapping outranks Spring's welcome page), the
 * [ContentPage] routes, the other known client routes (`SpaForwardController`'s
 * `forward:/index.html`) and unknown paths (`SpaErrorController`'s forward on a 404). The handlers
 * write the body straight to the response and never set a status, so a forwarded 404 stays a 404.
 *
 * A content page (`/terms`, `/privacy`, …) gets its own rendering: its title, description and
 * canonical URL in the head, and its text inside `#root`, where it stays visible until the SPA mounts
 * (and the SPA keeps showing it while the page's chunk loads — see `src/prerendered.ts`).
 *
 * `index.html` is a Handlebars template produced by the Vite build: `{{appName}}` / `{{homeUrl}}`
 * are HTML-escaped, `{{json …}}` emits a JSON value that is safe inside a `<script>` element, and
 * `{{#if page}}` switches between the content-page and default head/body. Every variant is rendered
 * once, on first request — configuration doesn't change at runtime.
 */
@Controller
class IndexHtmlController(
    private val publicConfigProvider: PublicConfigProvider,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val contentPageRenderer: ContentPageRenderer,
) {

    private val indexTemplate: String? by lazy { readStatic("index.html") }

    private val indexHtml: Rendered? by lazy { indexTemplate?.let { Rendered(renderIndex(it)) } }

    private val contentPages: Map<ContentPage, Rendered> by lazy {
        val template = indexTemplate ?: return@lazy emptyMap()
        ContentPage.entries.mapNotNull { page ->
            contentPageRenderer.renderBody(page)?.let { body -> page to Rendered(renderIndex(template, page, body)) }
        }.toMap()
    }

    private val manifest: Rendered? by lazy { readStatic("manifest.json")?.let { Rendered(renderManifest(it)) } }

    @GetMapping("/", "/index.html")
    fun index(request: HttpServletRequest, response: HttpServletResponse) =
        write(indexHtml, "text/html;charset=UTF-8", request, response)

    /** The paths here must match [ContentPage.path]; `IndexHtmlIntegrationTest` checks every entry. */
    @GetMapping("/terms", "/privacy", "/about", "/faq")
    fun contentPage(request: HttpServletRequest, response: HttpServletResponse) {
        val page = ContentPage.forPath(request.servletPath)
        // Without the markdown (never in a real build), the plain SPA document still renders the page.
        write(page?.let { contentPages[it] } ?: indexHtml, "text/html;charset=UTF-8", request, response)
    }

    @GetMapping("/manifest.json")
    fun manifest(request: HttpServletRequest, response: HttpServletResponse) =
        write(manifest, "application/manifest+json;charset=UTF-8", request, response)

    internal fun renderIndex(template: String, page: ContentPage? = null, body: String? = null): String {
        val handlebars = Handlebars().apply {
            registerHelper("json", Helper<Any?> { context, _ -> Handlebars.SafeString(scriptSafeJson(context)) })
        }
        val pageModel = page?.let {
            mapOf(
                "title" to it.title,
                "description" to contentPageRenderer.fillPlaceholders(it.description),
                "url" to appProperties.baseUrl + it.path,
                "body" to body,
            )
        }
        return handlebars.compileInline(template).apply(
            mapOf(
                "appName" to appProperties.name,
                "homeUrl" to "${appProperties.baseUrl}/",
                "config" to publicConfigProvider.config(),
                "page" to pageModel,
            ),
        )
    }

    internal fun renderManifest(source: String): String {
        val manifest = objectMapper.readTree(source) as ObjectNode
        manifest.put("name", appProperties.name)
        manifest.put("short_name", appProperties.name)
        return objectMapper.writeValueAsString(manifest)
    }

    /** JSON with `<`, `>` and `&` escaped, so no value can close the surrounding `<script>` element. */
    private fun scriptSafeJson(value: Any?): String =
        objectMapper.writeValueAsString(value)
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")

    private fun write(rendered: Rendered?, contentType: String, request: HttpServletRequest, response: HttpServletResponse) {
        if (rendered == null) {
            // Only in a build without the frontend bundled (some test setups); never forward to the
            // SPA error page from here, which would come straight back.
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }
        // Conditional GET only for a real 200: a forwarded 404 must not turn into a 304.
        val ok = response.status == HttpServletResponse.SC_OK
        if (ok && ServletWebRequest(request, response).checkNotModified(rendered.etag)) return
        response.contentType = contentType
        response.writer.write(rendered.body)
    }

    private fun readStatic(name: String): String? =
        ClassPathResource("static/$name").takeIf { it.exists() }
            ?.inputStream?.use { it.readBytes().toString(Charsets.UTF_8) }

    private class Rendered(val body: String) {
        val etag: String = "\"" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray()),
        ).take(32) + "\""
    }
}
