package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * `/.well-known/security.txt` — the RFC 9116 vulnerability-disclosure contact file.
 *
 * Generated rather than a static file, for the same reasons as [ApiCatalogController]: the contact
 * address and the `Canonical` URL are per instance ([AppProperties.supportEmail],
 * [AppProperties.baseUrl]), and the RFC requires an `Expires` field, which a checked-in file would
 * eventually let lapse. Because every value is read from live config, a rolling expiry is honest:
 * the file can't go stale while the instance is running.
 *
 * Contacts are listed in order of preference (RFC 9116 section 2.5.3). The GitHub private advisory
 * comes first because it reaches the upstream maintainer and keeps the report private; it applies
 * to every instance, since a code vulnerability is upstream's to fix. The instance's own support
 * address follows, for operator-side issues or reporters without a GitHub account.
 */
@RestController
class SecurityTxtController(
    private val appProperties: AppProperties,
    private val clock: Clock,
) {

    @GetMapping("/.well-known/security.txt", produces = ["text/plain;charset=UTF-8"])
    fun securityTxt(): String {
        val base = appProperties.baseUrl.trimEnd('/')
        val expires = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(EXPIRY)
        return """
            |Contact: $GITHUB_ADVISORY_URL
            |Contact: mailto:${appProperties.supportEmail}
            |Expires: $expires
            |Preferred-Languages: en
            |Canonical: $base/.well-known/security.txt
            |
        """.trimMargin()
    }

    companion object {
        const val GITHUB_ADVISORY_URL = "https://github.com/Itaypk/Taskboard/security/advisories/new"

        /** The RFC recommends an expiry less than a year out (section 2.5.5). */
        private val EXPIRY: Duration = Duration.ofDays(180)
    }
}
