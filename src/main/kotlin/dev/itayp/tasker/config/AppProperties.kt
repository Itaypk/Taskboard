package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.app")
data class AppProperties(
    val baseUrl: String = "https://backlog.fyi",
    /**
     * Product name shown to users: emails, Telegram messages, the web UI and the assistant's
     * persona. Operator-configured, so it's trusted, but it's inserted into HTML emails and into
     * `MessageFormat` patterns — hence the character restrictions checked below.
     */
    val name: String = "Backlog.fyi",
    /** Public contact address shown in the web UI (policy pages, error screens). */
    val supportEmail: String = "hello@backlog.fyi",
    /** Where abuse reports go; referenced from the terms, privacy policy and FAQ. */
    val abuseEmail: String = "abuse@backlog.fyi",
    /**
     * Hard cap on how many accounts may sit unclaimed (no login identity yet) at once — a safety
     * valve against a scripted signup surge exhausting the demo/unclaimed pool, separate from the
     * inactivity-based cleanup sweep ([dev.itayp.tasker.service.UnclaimedAccountCleanupService])
     * which already reclaims stale ones on a schedule. Deliberately generous: it exists to blunt a
     * surge, not to constrain normal usage.
     */
    val unclaimedAccountCap: Int = 1000,
) {
    init {
        require(name.isNotBlank() && name.length <= 64) { "TASKER_APP_NAME must be 1-64 characters" }
        require(name.none { it in NAME_FORBIDDEN }) {
            "TASKER_APP_NAME must not contain any of $NAME_FORBIDDEN (it's used in HTML emails and message patterns)"
        }
    }

    companion object {
        private const val NAME_FORBIDDEN = "<>&\"'{}"
    }
}
