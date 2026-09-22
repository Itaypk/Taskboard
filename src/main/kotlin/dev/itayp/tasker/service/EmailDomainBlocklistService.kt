package dev.itayp.tasker.service

import dev.itayp.tasker.channel.email.EmailProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.zip.GZIPInputStream

/**
 * Application-wide email domain blocklist. Every entry point that accepts a new address
 * (magic-link login, settings email change, board invitations) checks here first, so a domain
 * only needs to be blocked in one place.
 *
 * Two sources, combined additively:
 *
 *  1. A **vendored snapshot** of https://github.com/disposable/disposable-email-domains (MIT),
 *     ~75k throwaway-mail domains, shipped gzipped on the classpath and refreshed by
 *     `tools/refresh-disposable-domains.sh`. Vendored rather than fetched at runtime so boot has
 *     no network dependency and tests run offline. It costs roughly 6-8 MB of heap once expanded;
 *     if that ever matters, a prefix-trie or a bloom filter is the next step, not a runtime fetch.
 *  2. [EmailProperties.blockedDomains] from configuration, for anything we want to block on top.
 *
 * Config entries can never *unblock* a bundled domain — the two sets are unioned, never diffed.
 */
@Service
class EmailDomainBlocklistService(properties: EmailProperties) {

    private val log = LoggerFactory.getLogger(EmailDomainBlocklistService::class.java)

    /** The vendored disposable-domain snapshot. Exact matches only — see [isBlocked]. */
    private val bundledDomains: Set<String> = loadBundledDomains()

    private val exactDomains: Set<String> = properties.blockedDomains
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("*.") }
        .toSet()

    private val wildcardSuffixes: Set<String> = properties.blockedDomains
        .map { it.trim().lowercase() }
        .filter { it.startsWith("*.") }
        .map { it.removePrefix("*") }
        .toSet()

    /**
     * True if [email]'s domain is blocked. The bundled list matches **exactly**: it already
     * enumerates the subdomains it cares about, and treating each of its 75k entries as a suffix
     * would over-block (an entry `mail.com` would swallow `notmail.com`). The `*.` wildcard
     * semantics stay a configuration-only feature.
     */
    fun isBlocked(email: String): Boolean {
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        if (domain.isEmpty()) return false
        return domain in bundledDomains ||
            domain in exactDomains ||
            wildcardSuffixes.any(domain::endsWith)
    }

    /** Throws [BlockedEmailDomainException] if [email]'s domain is blocked. */
    fun requireAllowed(email: String) {
        if (isBlocked(email)) throw BlockedEmailDomainException()
    }

    /**
     * Fails **soft**: a packaging mistake must not stop the app booting, because every login path
     * runs through this class. Losing the list degrades to "config-only blocklist", which is
     * exactly the behavior that shipped before the list existed.
     */
    private fun loadBundledDomains(): Set<String> {
        val stream = javaClass.getResourceAsStream(BUNDLED_RESOURCE)
        if (stream == null) {
            log.warn("Bundled disposable-domain list {} not found; blocklist is config-only", BUNDLED_RESOURCE)
            return emptySet()
        }
        return try {
            stream.use { raw ->
                GZIPInputStream(raw).bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toSet()
                }
            }.also { log.info("Loaded {} bundled disposable email domains", it.size) }
        } catch (e: Exception) {
            log.warn("Failed to read bundled disposable-domain list; blocklist is config-only", e)
            emptySet()
        }
    }

    companion object {
        private const val BUNDLED_RESOURCE = "/email/disposable-domains.txt.gz"
    }
}

/** The submitted address's domain is on the application's email blocklist. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class BlockedEmailDomainException : RuntimeException("This email domain isn't allowed")
