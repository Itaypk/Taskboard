package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Session lifetime knobs. Spring Session owns the *idle* timeout (`spring.session.timeout`,
 * 30 days, sliding); this owns the *absolute* cap enforced by
 * [dev.itayp.tasker.security.AbsoluteSessionLifetimeFilter], which no amount of activity extends.
 *
 * The cap exists because a sliding timeout alone lets an attacker holding a stolen cookie keep it
 * alive forever. It is a property rather than a constant so the value can be tightened without a
 * code change if the threat model shifts.
 */
@ConfigurationProperties("tasker.session")
data class SessionProperties(
    val maxLifetime: Duration = Duration.ofDays(365),
)
