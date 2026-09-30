package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/.well-known/api-catalog` — the RFC 9727 discovery document for the external API.
 *
 * Generated rather than served as a static file (the way robots.txt and sitemap.xml are) for two
 * reasons the RFC forces on us:
 *  - the response MUST be `application/linkset+json`, and an extensionless static file would be
 *    served as `application/octet-stream`;
 *  - the links MUST be absolute, so a hardcoded `https://backlog.fyi` would be wrong on localhost.
 *
 * Both fall out of building the URLs from [AppProperties.baseUrl], the same source the email and
 * OIDC redirect links use. A pleasant side effect is that no dotted `.well-known/` directory has
 * to exist on disk.
 *
 * The document is a linkset: one entry anchored at the catalogue listing each API, then one entry
 * per API carrying its `service-desc` (machine-readable, the OpenAPI contract) and `service-doc`
 * (human-readable, the skill) links — the RFC 8631 relation types.
 */
@RestController
class ApiCatalogController(private val appProperties: AppProperties) {

    @GetMapping("/.well-known/api-catalog", produces = [LINKSET_JSON])
    fun apiCatalog(): Map<String, Any> {
        val base = appProperties.baseUrl.trimEnd('/')
        val apiUrl = "$base/api/external/v1"
        return mapOf(
            "linkset" to listOf(
                mapOf(
                    "anchor" to "$base/.well-known/api-catalog",
                    "item" to listOf(
                        mapOf(
                            "href" to apiUrl,
                            "title" to "${appProperties.name} External API v1",
                        ),
                    ),
                ),
                mapOf(
                    "anchor" to apiUrl,
                    "service-desc" to listOf(
                        mapOf(
                            "href" to "$base/external-api/openapi.yaml",
                            "type" to "application/yaml",
                            "title" to "OpenAPI 3.1 description",
                        ),
                    ),
                    "service-doc" to listOf(
                        mapOf(
                            "href" to "$base/external-api/SKILL.md",
                            "type" to "text/markdown",
                            "title" to "Usage guide for AI assistants",
                        ),
                    ),
                ),
            ),
        )
    }

    companion object {
        /** RFC 9264. The catalogue MUST be available in this format (RFC 9727 section 3). */
        const val LINKSET_JSON = "application/linkset+json"
    }
}
