package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Inbound email (`tasker.inbound-mail`): users get a personal address on [domain], and mail sent
 * there becomes a task. Receiving needs an MTA in front of the app that accepts mail for [domain]
 * and hands it over (`POST /internal/inbound-mail`); the hosted instance's is in the ops repo. Off
 * unless the domain and the delivery token are both set, so a self-hosted instance never shows
 * addresses nothing will deliver to.
 */
@ConfigurationProperties("tasker.inbound-mail")
data class InboundMailProperties(
    val enabled: Boolean = false,
    /** The domain the addresses live on, e.g. `m.backlog.fyi`. */
    val domain: String = "",
    /**
     * Shared secret the MTA sends as `X-Inbound-Mail-Token` on every delivery to
     * `/internal/inbound-mail`. At least [MIN_DELIVERY_TOKEN_LENGTH] characters.
     */
    val deliveryToken: String = "",
    /**
     * The authserv-id of the `Authentication-Results` header the MTA's verifier (rspamd) adds.
     * Only that header is trusted for "verified sender"; any other is the sender's own claim.
     * Defaults to [domain].
     */
    val authservId: String = "",
) {
    val normalizedDomain: String = domain.trim().lowercase()

    val effectiveAuthservId: String = authservId.trim().lowercase().ifEmpty { normalizedDomain }

    /** Whether the feature is on: enabled, with a domain for the addresses and a token for delivery. */
    val active: Boolean get() = enabled && normalizedDomain.isNotEmpty() && deliveryToken.length >= MIN_DELIVERY_TOKEN_LENGTH

    init {
        require(normalizedDomain.isEmpty() || DOMAIN.matches(normalizedDomain)) {
            "TASKER_INBOUND_MAIL_DOMAIN must be a domain name like m.example.com (got '$domain')"
        }
    }

    /** Never print the delivery token (data classes include every field in toString). */
    override fun toString(): String =
        "InboundMailProperties(enabled=$enabled, domain=$normalizedDomain, deliveryToken=${if (deliveryToken.isEmpty()) "unset" else "***"}, " +
            "authservId=$effectiveAuthservId)"

    companion object {
        const val MIN_DELIVERY_TOKEN_LENGTH = 32
        private val DOMAIN = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    }
}
