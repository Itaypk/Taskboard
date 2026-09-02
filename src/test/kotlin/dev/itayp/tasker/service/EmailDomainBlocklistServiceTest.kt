package dev.itayp.tasker.service

import dev.itayp.tasker.channel.email.EmailProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmailDomainBlocklistServiceTest {

    @Test
    fun `exact domain entries block only that domain`() {
        val service = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("spam.com")))

        assertThat(service.isBlocked("user@spam.com")).isTrue()
        assertThat(service.isBlocked("user@mail.spam.com")).isFalse()
        assertThat(service.isBlocked("user@notspam.com")).isFalse()
    }

    @Test
    fun `wildcard entries block subdomains but not the bare domain`() {
        val service = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("*.spam.com")))

        assertThat(service.isBlocked("user@mail.spam.com")).isTrue()
        assertThat(service.isBlocked("user@a.b.spam.com")).isTrue()
        assertThat(service.isBlocked("user@spam.com")).isFalse()
        assertThat(service.isBlocked("user@evil-spam.com")).isFalse()
    }

    @Test
    fun `listing both forms blocks the domain and its subdomains`() {
        val service = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("spam.com", "*.spam.com")))

        assertThat(service.isBlocked("user@spam.com")).isTrue()
        assertThat(service.isBlocked("user@mail.spam.com")).isTrue()
    }

    @Test
    fun `matching is case-insensitive and ignores surrounding whitespace`() {
        val service = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("Spam.com")))

        assertThat(service.isBlocked("  User@SPAM.COM  ")).isTrue()
    }

    @Test
    fun `requireAllowed throws only for a blocked domain`() {
        val service = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("spam.com")))

        assertThrows<BlockedEmailDomainException> { service.requireAllowed("user@spam.com") }
        service.requireAllowed("user@example.com")
    }

    @Test
    fun `an empty config blocklist still enforces the bundled disposable list`() {
        val service = EmailDomainBlocklistService(EmailProperties())

        assertThat(service.isBlocked("user@example.com")).isFalse()
        assertThat(service.isBlocked("user@mailinator.com")).isTrue()
    }

    @Test
    fun `the bundled list leaves mainstream providers alone`() {
        // The whole risk of shipping a 75k-domain list is a false positive locking real users out
        // of login. tools/refresh-disposable-domains.sh guards the same names on refresh; this
        // pins the guarantee at the point it actually matters.
        val service = EmailDomainBlocklistService(EmailProperties())

        listOf("gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "protonmail.com", "proton.me", "icloud.com")
            .forEach { assertThat(service.isBlocked("user@$it")).describedAs(it).isFalse() }
    }

    @Test
    fun `the bundled list matches exactly, not by suffix`() {
        // Config entries can opt into subdomain matching with `*.`; bundled entries cannot, or a
        // short entry would swallow unrelated domains.
        val service = EmailDomainBlocklistService(EmailProperties())

        assertThat(service.isBlocked("user@mailinator.com")).isTrue()
        assertThat(service.isBlocked("user@not-mailinator.com")).isFalse()
    }

    @Test
    fun `config entries add to the bundled list rather than replacing it`() {
        val service = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("spam.com")))

        assertThat(service.isBlocked("user@spam.com")).isTrue()
        assertThat(service.isBlocked("user@mailinator.com")).isTrue()
    }
}
