package dev.itayp.tasker.controller

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.config.AppProperties
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * The static content pages whose text the server renders into the document itself (see
 * [IndexHtmlController]), so crawlers, link-preview bots and LLM agents that don't run JavaScript get
 * the page — with its own title and canonical URL — rather than the landing page.
 *
 * The markdown is the same file the SPA renders (`tasker-frontend/src/auth/`, copied to `content/` on
 * the classpath by `processResources`). Titles and descriptions are English, like the pages.
 * `{{APP_NAME}}` in a description is filled in like in the markdown.
 */
enum class ContentPage(val path: String, val file: String, val title: String, val description: String) {
    TERMS(
        "/terms", "tos.md", "Terms of Service",
        "The terms for using {{APP_NAME}}: your data, third-party services, acceptable use, and the lack of guarantees.",
    ),
    PRIVACY(
        "/privacy", "privacy-policy.md", "Privacy Policy",
        "What data {{APP_NAME}} collects, how it is protected and used, which third parties are involved, and how to export or delete it.",
    ),
    ABOUT(
        "/about", "about.md", "About",
        "Why {{APP_NAME}} exists, who builds and runs it, and what state it's in.",
    ),
    FAQ(
        "/faq", "faq.md", "Frequently asked questions",
        "What {{APP_NAME}} is, how it works, how your data is handled, and what it costs.",
    ),
    ;

    companion object {
        fun forPath(path: String): ContentPage? = entries.firstOrNull { it.path == path }
    }
}

/**
 * The privacy policy's sentence about AI data retention, present only while it's true. Mirrored in the
 * SPA's `PolicyPage.tsx`.
 */
const val AI_RETENTION_NOTE =
    "Requests to AI providers are only routed to endpoints with a zero-data-retention policy: the " +
        "provider doesn't store your input or its response once the request completes."

/** Renders a [ContentPage]'s markdown to HTML, with this instance's name, contact addresses and settings filled in. */
@Component
class ContentPageRenderer(
    private val appProperties: AppProperties,
    private val aiProperties: AiProperties,
) {

    private val parser = Parser.builder().build()

    // The markdown is our own, but there's no reason to let raw HTML through.
    private val renderer = HtmlRenderer.builder().escapeHtml(true).build()

    /** The page body as HTML, or null when the markdown isn't on the classpath. */
    fun renderBody(page: ContentPage): String? =
        readMarkdown(page)?.let { renderer.render(parser.parse(fillPlaceholders(it))) }

    /** Same placeholders as the SPA's `PolicyPage`. */
    fun fillPlaceholders(text: String): String =
        text.replace("{{APP_NAME}}", appProperties.name)
            .replace("{{APP_URL}}", appProperties.baseUrl)
            .replace("{{SUPPORT_EMAIL}}", appProperties.supportEmail)
            .replace("{{ABUSE_EMAIL}}", appProperties.abuseEmail)
            .replace("{{AI_RETENTION_NOTE}}", if (aiProperties.zeroDataRetentionInEffect) AI_RETENTION_NOTE else "")

    private fun readMarkdown(page: ContentPage): String? =
        ClassPathResource("content/${page.file}").takeIf { it.exists() }
            ?.inputStream?.use { it.readBytes().toString(Charsets.UTF_8) }
}
