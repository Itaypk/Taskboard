package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.rate-limit")
data class RateLimitProperties(
    val api: Policy = Policy(limit = 300, windowSeconds = 60),
    val demoLogin: Policy = Policy(limit = 5, windowSeconds = 3600),
    val telegramLogin: Policy = Policy(limit = 30, windowSeconds = 60),
    /**
     * Magic-link *sends* per client IP. [dev.itayp.tasker.service.EmailLoginService] already caps
     * sends per address, but that alone lets one host mail unlimited *distinct* strangers from the
     * auth sender — the mailbox that login itself depends on. This bounds the sender's blast radius;
     * the two limits are complementary, so keep both.
     */
    val emailLogin: Policy = Policy(limit = 10, windowSeconds = 3600),
    val emailVerification: Policy = Policy(limit = 5, windowSeconds = 3600),
    val feedback: Policy = Policy(limit = 10, windowSeconds = 3600),
    /**
     * Captures opened per user per hour. Free text sent to the bot is a capture now, so `/add` is
     * no longer the throttle it used to be — this bounds what a runaway forwarding loop can cost.
     * Generous for a human: the limit is about a stuck client, not about rationing.
     */
    val quickAdd: Policy = Policy(limit = 60, windowSeconds = 3600),
    val externalApi: Policy = Policy(limit = 120, windowSeconds = 60),
    /**
     * Password attempts, counted separately per client IP and per username by
     * [dev.itayp.tasker.service.LocalLoginService]. The per-username budget lets anyone lock a known
     * username out for one window — an accepted trade on the small instances local users are for.
     */
    val passwordLogin: Policy = Policy(limit = 10, windowSeconds = 900),
) {
    data class Policy(val limit: Int, val windowSeconds: Long)
}
