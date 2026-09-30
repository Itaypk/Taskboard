package dev.itayp.tasker.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Fails startup on `prod` configuration that would boot but misbehave. Only settings with no safe
 * default belong here; optional integrations (Telegram, email, AI, metrics) degrade instead and are
 * reported by [StartupSummaryLogger].
 *
 * `@ConfigurationProperties` binding leaves an unresolvable `${VAR}` placeholder in place as a
 * literal string rather than failing, so a missing variable has to be caught explicitly.
 */
@Component
@Profile("prod")
class ProductionConfigValidator(private val appProperties: AppProperties) : InitializingBean {

    override fun afterPropertiesSet() {
        validateBaseUrl(appProperties.baseUrl)?.let { throw IllegalStateException(it) }
    }

    companion object {
        /** Returns a human-readable problem, or null when [baseUrl] is usable. */
        fun validateBaseUrl(baseUrl: String): String? {
            if (baseUrl.isBlank()) {
                return "TASKER_APP_BASE_URL is required: the public URL users reach this instance at, " +
                    "e.g. https://tasks.example.com (used for login links, invitations and CORS)"
            }
            val uri = runCatching { URI(baseUrl) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                return "TASKER_APP_BASE_URL must be an absolute http(s) URL, e.g. https://tasks.example.com; got '$baseUrl'"
            }
            if (baseUrl.endsWith("/")) {
                return "TASKER_APP_BASE_URL must not end with '/'; got '$baseUrl'"
            }
            return null
        }
    }
}
