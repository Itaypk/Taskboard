package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.EmailLoginTokenEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.EmailLoginTokenRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    private val service = EmailLoginService(
        tokenRepository, userAuthService, outboundChannel, templateEngine,
        messageSource, crypto, appProperties, clock,
    )

    @Test
    fun `requestLogin stores an encrypted token and sends a login email`() {
        whenever(tokenRepository.countByEmailHashAndCreatedAtAfter(any(), any())).thenReturn(0)
        whenever(templateEngine.render(any(), any(), any())).thenReturn("<html>")
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

        val msgCaptor = argumentCaptor<EmailMessage>()
        verify(outboundChannel).send(msgCaptor.capture())
        assertThat(msgCaptor.firstValue.to).containsExactly("user@example.com")
    }

    @Test
    fun `requestLogin is suppressed when the address is over the rate limit`() {
        whenever(tokenRepository.countByEmailHashAndCreatedAtAfter(any(), any())).thenReturn(5)

        service.requestLogin("user@example.com")

        verify(tokenRepository, never()).save(any())
        verify(outboundChannel, never()).send(any())
    }

    private fun tokenRow(
        email: String,
        consumed: Instant? = null,
        expiresAt: Instant = fixedNow.plusSeconds(600),
    ) = EmailLoginTokenEntity().apply {
        token = "tok"
        emailHash = EmailHasher.hash(email)
        emailEnc = crypto.encryptSystem(email)
        createdAt = fixedNow
        this.expiresAt = expiresAt
        consumedAt = consumed
    }

    @Test
    fun `completeLogin consumes the token and returns the resolved user`() {
        val user = UserEntity().apply { id = UUID.randomUUID() }
        whenever(tokenRepository.findById("tok")).thenReturn(Optional.of(tokenRow("user@example.com")))
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
        whenever(tokenRepository.findById("nope")).thenReturn(Optional.empty())
        assertThat(service.completeLogin("nope")).isEqualTo(EmailLoginResult.Invalid)
    }

    @Test
    fun `completeLogin rejects a replayed token without resolving an account`() {
        whenever(tokenRepository.findById("tok"))
            .thenReturn(Optional.of(tokenRow("user@example.com", consumed = fixedNow.minusSeconds(60))))

        assertThat(service.completeLogin("tok")).isEqualTo(EmailLoginResult.Invalid)
        verifyNoInteractions(userAuthService)
    }

    @Test
    fun `completeLogin rejects an expired token`() {
        whenever(tokenRepository.findById("tok"))
            .thenReturn(Optional.of(tokenRow("user@example.com", expiresAt = fixedNow.minusSeconds(1))))

        assertThat(service.completeLogin("tok")).isEqualTo(EmailLoginResult.Invalid)
    }

    @Test
    fun `completeLogin surfaces an unverified conflict`() {
        whenever(tokenRepository.findById("tok")).thenReturn(Optional.of(tokenRow("user@example.com")))
        whenever(userAuthService.loginByEmail("user@example.com")).thenReturn(EmailLoginOutcome.UnverifiedConflict)

        assertThat(service.completeLogin("tok")).isEqualTo(EmailLoginResult.UnverifiedConflict)
    }
}
