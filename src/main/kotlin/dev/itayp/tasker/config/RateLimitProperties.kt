package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tasker.rate-limit")
data class RateLimitProperties(
    val api: Policy = Policy(limit = 300, windowSeconds = 60),
    val demoLogin: Policy = Policy(limit = 5, windowSeconds = 3600),
    val telegramLogin: Policy = Policy(limit = 30, windowSeconds = 60),
    val emailVerification: Policy = Policy(limit = 5, windowSeconds = 3600),
    val feedback: Policy = Policy(limit = 10, windowSeconds = 3600),
    val externalApi: Policy = Policy(limit = 120, windowSeconds = 60),
) {
    data class Policy(val limit: Int, val windowSeconds: Long)
}
