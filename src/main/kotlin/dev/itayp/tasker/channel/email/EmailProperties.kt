package dev.itayp.tasker.channel.email

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Email configuration, split into two independent senders so the critical auth path
 * (login / register / verification magic links) can use a different mailbox — and a
 * different SMTP account — from scheduling (calendar invites). Isolating them means a
 * deliverability problem on one mailbox can't take down login email.
 *
 * Each sender carries its own full SMTP credentials because providers like Protonmail
 * tie SMTP submission auth to the sending address.
 *
 * [blockedDomains] adds to the application-wide email domain blocklist enforced by
 * `EmailDomainBlocklistService` wherever a new address is accepted (login, settings, board
 * invites). The bulk of that blocklist is a bundled ~75k-domain disposable-mail snapshot; this
 * key is the manual override on top of it, and can only add, never unblock. An entry matches
 * that domain exactly, except entries prefixed with `*.`, which match any subdomain of the given
 * domain but not the domain itself — list both forms to block a site and all its subdomains
 * (e.g. `mailinator.com` and `*.mailinator.com`).
 */
@ConfigurationProperties("tasker.email")
data class EmailProperties(
    val enabled: Boolean = false,
    val auth: SenderConfig = SenderConfig(),
    val scheduling: SenderConfig = SenderConfig(),
    val blockedDomains: Set<String> = emptySet(),
) {
    data class SenderConfig(
        val from: String = "",
        val fromName: String = "Backlog.fyi",
        val smtp: SmtpConfig = SmtpConfig(),
    )

    data class SmtpConfig(
        val host: String = "smtp.protonmail.ch",
        val port: Int = 587,
        val username: String = "",
        val password: String = "",
    )
}
