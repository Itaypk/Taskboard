package dev.itayp.tasker.service

import dev.itayp.tasker.channel.email.EmailProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Application-wide email domain blocklist, configured via [EmailProperties.blockedDomains].
 * Every entry point that accepts a new address (magic-link login, settings email change,
 * board invitations) checks here first, so a domain only needs to be blocked in one place.
 */
@Service
class EmailDomainBlocklistService(properties: EmailProperties) {

    private val exactDomains: Set<String> = properties.blockedDomains
        .map { it.trim().lowercase() }
        .filterTo(mutableSetOf()) { it.isNotEmpty() && !it.startsWith("*.") }

    private val wildcardSuffixes: Set<String> = properties.blockedDomains
        .map { it.trim().lowercase() }
        .filter { it.startsWith("*.") }
        .mapTo(mutableSetOf()) { it.removePrefix("*") }

    /** True if [email]'s domain is blocked, either by an exact match or a wildcard suffix. */
    fun isBlocked(email: String): Boolean {
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        return domain.isNotEmpty() && (domain in exactDomains || wildcardSuffixes.any(domain::endsWith))
    }

    /** Throws [BlockedEmailDomainException] if [email]'s domain is blocked. */
    fun requireAllowed(email: String) {
        if (isBlocked(email)) throw BlockedEmailDomainException()
    }
}

/** The submitted address's domain is on the application's email blocklist. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class BlockedEmailDomainException : RuntimeException("This email domain isn't allowed")
