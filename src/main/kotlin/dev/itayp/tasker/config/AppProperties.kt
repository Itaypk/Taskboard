package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.app")
data class AppProperties(
    val baseUrl: String = "https://backlog.fyi",
    /**
     * Hard cap on how many accounts may sit unclaimed (no login identity yet) at once — a safety
     * valve against a scripted signup surge exhausting the demo/unclaimed pool, separate from the
     * inactivity-based cleanup sweep ([dev.itayp.tasker.service.UnclaimedAccountCleanupService])
     * which already reclaims stale ones on a schedule. Deliberately generous: it exists to blunt a
     * surge, not to constrain normal usage.
     */
    val unclaimedAccountCap: Int = 1000,
)
