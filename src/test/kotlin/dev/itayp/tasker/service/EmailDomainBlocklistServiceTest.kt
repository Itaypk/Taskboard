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
    fun `an empty blocklist blocks nothing`() {
        val service = EmailDomainBlocklistService(EmailProperties())

        assertThat(service.isBlocked("user@example.com")).isFalse()
    }
}
