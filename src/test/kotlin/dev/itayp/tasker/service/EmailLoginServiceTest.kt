package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.config.AuthProperties
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.EmailLoginTokenEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.EmailLoginTokenRepository
import dev.itayp.tasker.util.CapabilityTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class EmailLoginServiceTest {

    private val fixedNow = Instant.parse("2026-04-21T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val crypto = newTestUserCryptoService()

    private val tokenRepository: EmailLoginTokenRepository = mock()
    private val userAuthService: UserAuthService = mock()
    private val outboundChannel: OutboundChannel = mock()
    private val templateEngine: EmailTemplateEngine = mock()
    private val messageSource: MessageSource = mock()
    private val appProperties = AppProperties(baseUrl = "https://backlog.fyi")
    private val blocklist = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("blocked.example")))

    private val localeNegotiationService = LocaleNegotiationService()

    private val service = serviceWith(RegistrationPolicy(AuthProperties()))

    private fun serviceWith(registrationPolicy: RegistrationPolicy) = EmailLoginService(
        tokenRepository, userAuthService, outboundChannel, templateEngine,
        messageSource, crypto, appProperties, blocklist, localeNegotiationService, registrationPolicy, clock,
    )

    @Test
    fun `requestLogin stores an encrypted token and sends a login email with confirm-page URL`() {
        whenever(tokenRepository.countByEmailHashAndCreatedAtAfter(any(), any())).thenReturn(0)
        val templateVars = argumentCaptor<Map<String, Any>>()
        whenever(templateEngine.render(any(), templateVars.capture(), any())).thenReturn("<html>")
        whenever(messageSource.getMessage(eq("email.login.subject"), anyOrNull(), any())).thenReturn("Sign in")
        whenever(messageSource.getMessage(eq("email.login.textBody"), any(), any())).thenReturn("text")

        service.requestLogin("  User@Example.com ")

        val tokenCaptor = argumentCaptor<EmailLoginTokenEntity>()
        verify(tokenRepository).save(tokenCaptor.capture())
        val saved = tokenCaptor.firstValue
        assertThat(saved.emailHash).isEqualTo(EmailHasher.hash("user@example.com"))
        assertThat(crypto.decryptSystem(saved.emailEnc)).isEqualTo("user@example.com")
        assertThat(saved.expiresAt).isEqualTo(fixedNow.plusSeconds(1800))
        assertThat(saved.consumedAt).isNull()

        val loginUrl = templateVars.firstValue["login_url"] as String
        assertThat(loginUrl).startsWith("https://backlog.fyi/email-login?token=")
        // Only the digest is persisted; the plaintext exists solely in the emailed link.
        val emailedToken = loginUrl.substringAfter("token=")
        assertThat(saved.tokenHash).isEqualTo(CapabilityTokens.hash(emailedToken))
        assertThat(saved.tokenHash).isNotEqualTo(emailedToken)
        assertThat(loginUrl).doesNotContain("/api/auth/email/callback")

        val msgCaptor = argumentCaptor<EmailMessage>()
        verify(outboundChannel).send(msgCaptor.capture())
        assertThat(msgCaptor.firstValue.to).containsExactly("user@example.com")
    }

    @Test
    fun `requestLogin threads a valid next path into the login URL`() {
        whenever(tokenRepository.countByEmailHashAndCreatedAtAfter(any(), any())).thenReturn(0)
        val templateVars = argumentCaptor<Map<String, Any>>()
        whenever(templateEngine.render(any(), templateVars.capture(), any())).thenReturn("<html>")
        whenever(messageSource.getMessage(any(), anyOrNull(), any())).thenReturn("text")

        service.requestLogin("user@example.com", next = "/invite?token=abc")

        val loginUrl = templateVars.firstValue["login_url"] as String
        // next= should be URL-encoded in the link; /invite?token=abc → %2Finvite%3Ftoken%3Dabc
        assertThat(loginUrl).contains("next=%2Finvite")
    }

    @Test
    fun `requestLogin rejects an absolute next URL and omits it from the login link`() {
        whenever(tokenRepository.countByEmailHashAndCreatedAtAfter(any(), any())).thenReturn(0)
        val templateVars = argumentCaptor<Map<String, Any>>()
        whenever(templateEngine.render(any(), templateVars.capture(), any())).thenReturn("<html>")
        whenever(messageSource.getMessage(any(), anyOrNull(), any())).thenReturn("text")

        service.requestLogin("user@example.com", next = "https://evil.example.com/steal")

        val loginUrl = templateVars.firstValue["login_url"] as String
        assertThat(loginUrl).doesNotContain("next=")
    }

    @Test
    fun `requestLogin is suppressed when the address is over the rate limit`() {
        whenever(tokenRepository.countByEmailHashAndCreatedAtAfter(any(), any())).thenReturn(5)

        service.requestLogin("user@example.com")

        verify(tokenRepository, never()).save(any())
        verify(outboundChannel, never()).send(any())
    }

    @Test
    fun `requestLogin rejects a blocklisted domain loudly, and sends nothing`() {
        // Loud, unlike the rate-limit case above: a blocked domain says nothing about whether an
        // account exists, and with ~75k bundled disposable domains a silent no-op would make a
        // false positive indistinguishable from mail that never arrived.
        assertThrows<BlockedEmailDomainException> { service.requestLogin("user@blocked.example") }

        verify(tokenRepository, never()).countByEmailHashAndCreatedAtAfter(any(), any())
        verify(tokenRepository, never()).save(any())
        verify(outboundChannel, never()).send(any())
    }

    @Test
    fun `requestLogin rejects a domain from the bundled disposable list`() {
        // Not in the test's configured blocklist — this proves the bundled snapshot is wired in.
        assertThrows<BlockedEmailDomainException> { service.requestLogin("user@mailinator.com") }

        verify(outboundChannel, never()).send(any())
    }

    private fun tokenRow(
        email: String,
        consumed: Instant? = null,
        expiresAt: Instant = fixedNow.plusSeconds(600),
    ) = EmailLoginTokenEntity().apply {
        tokenHash = CapabilityTokens.hash("tok")
        emailHash = EmailHasher.hash(email)
        emailEnc = crypto.encryptSystem(email)
        createdAt = fixedNow
        this.expiresAt = expiresAt
        consumedAt = consumed
    }

    @Test
    fun `precheckToken returns true for a valid unconsumed token`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok"))).thenReturn(Optional.of(tokenRow("user@example.com")))
        assertThat(service.precheckToken("tok")).isTrue()
    }

    @Test
    fun `precheckToken returns false for an unknown token`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("nope"))).thenReturn(Optional.empty())
        assertThat(service.precheckToken("nope")).isFalse()
    }

    @Test
    fun `precheckToken returns false for a consumed token`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok")))
            .thenReturn(Optional.of(tokenRow("user@example.com", consumed = fixedNow.minusSeconds(1))))
        assertThat(service.precheckToken("tok")).isFalse()
    }

    @Test
    fun `precheckToken returns false for an expired token`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok")))
            .thenReturn(Optional.of(tokenRow("user@example.com", expiresAt = fixedNow.minusSeconds(1))))
        assertThat(service.precheckToken("tok")).isFalse()
    }

    @Test
    fun `completeLogin consumes the token and returns the resolved user`() {
        val user = UserEntity().apply { id = UUID.randomUUID() }
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok"))).thenReturn(Optional.of(tokenRow("user@example.com")))
        whenever(userAuthService.loginByEmail("user@example.com")).thenReturn(EmailLoginOutcome.Success(user))

        val result = service.completeLogin("tok")

        assertThat(result).isInstanceOf(EmailLoginResult.Success::class.java)
        assertThat((result as EmailLoginResult.Success).user).isSameAs(user)
        val captor = argumentCaptor<EmailLoginTokenEntity>()
        verify(tokenRepository).save(captor.capture())
        assertThat(captor.firstValue.consumedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `completeLogin rejects an unknown token`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("nope"))).thenReturn(Optional.empty())
        assertThat(service.completeLogin("nope")).isEqualTo(EmailLoginResult.Invalid)
    }

    @Test
    fun `completeLogin rejects a replayed token without resolving an account`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok")))
            .thenReturn(Optional.of(tokenRow("user@example.com", consumed = fixedNow.minusSeconds(60))))

        assertThat(service.completeLogin("tok")).isEqualTo(EmailLoginResult.Invalid)
        verifyNoInteractions(userAuthService)
    }

    @Test
    fun `completeLogin rejects an expired token`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok")))
            .thenReturn(Optional.of(tokenRow("user@example.com", expiresAt = fixedNow.minusSeconds(1))))

        assertThat(service.completeLogin("tok")).isEqualTo(EmailLoginResult.Invalid)
    }

    @Test
    fun `completeLogin surfaces an unverified conflict`() {
        whenever(tokenRepository.findById(CapabilityTokens.hash("tok"))).thenReturn(Optional.of(tokenRow("user@example.com")))
        whenever(userAuthService.loginByEmail("user@example.com")).thenReturn(EmailLoginOutcome.UnverifiedConflict)

        assertThat(service.completeLogin("tok")).isEqualTo(EmailLoginResult.UnverifiedConflict)
    }

    @Test
    fun `closed registration mails only addresses that already have an account`() {
        val closed = serviceWith(RegistrationPolicy(AuthProperties(registration = AuthProperties.RegistrationMode.CLOSED)))
        whenever(userAuthService.hasAccountForEmail(EmailHasher.hash("stranger@example.com"))).thenReturn(false)

        closed.requestLogin("stranger@example.com")

        verify(tokenRepository, never()).save(any<EmailLoginTokenEntity>())
        verify(outboundChannel, never()).send(any())
    }
}
