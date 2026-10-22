package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Inbound email (`tasker.inbound-mail`): users get a personal address on [domain], and mail sent
 * there becomes a task. Receiving needs an MTA in front of the app that accepts mail for [domain]
 * and hands it over; the hosted instance's is in the ops repo. Off unless both are set, so a
 * self-hosted instance never shows addresses nothing will deliver to.
 */
@ConfigurationProperties("tasker.inbound-mail")
data class InboundMailProperties(
    val enabled: Boolean = false,
    /** The domain the addresses live on, e.g. `m.backlog.fyi`. */
    val domain: String = "",
) {
    val normalizedDomain: String = domain.trim().lowercase()

    /** Whether the feature is on: enabled, with a domain to put the addresses on. */
    val active: Boolean get() = enabled && normalizedDomain.isNotEmpty()

    init {
        require(normalizedDomain.isEmpty() || DOMAIN.matches(normalizedDomain)) {
            "TASKER_INBOUND_MAIL_DOMAIN must be a domain name like m.example.com (got '$domain')"
        }
    }

    private companion object {
        val DOMAIN = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    }
}
