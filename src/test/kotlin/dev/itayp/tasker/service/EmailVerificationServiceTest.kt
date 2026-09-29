package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.ratelimit.InMemoryRateLimiter
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.util.CapabilityTokens
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.Optional
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailVerificationServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC)
    private val crypto = newTestUserCryptoService()
    private val userRepository: UserRepository = mock()
    private val authIdentityRepository: AuthIdentityRepository = mock()
    private val outboundChannel: OutboundChannel = mock()
    private val emailTemplateEngine: EmailTemplateEngine = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messageSource: MessageSource = mock()
    private val blocklist = EmailDomainBlocklistService(EmailProperties(blockedDomains = setOf("blocked.example")))
    private val rateLimiter = InMemoryRateLimiter(limit = 5, windowMillis = Duration.ofHours(1).toMillis())

    private val service = EmailVerificationService(
        userRepository, authIdentityRepository, outboundChannel, AppProperties(baseUrl = "https://test.local"),
        clock, emailTemplateEngine, userSettingsService, messageSource, crypto, blocklist, rateLimiter,
    )

    private fun primeSendingMocks() {
        whenever(userSettingsService.getLocale(any())).thenReturn(Locale.ENGLISH)
        whenever(emailTemplateEngine.render(any(), any(), any())).thenReturn("<html></html>")
        whenever(messageSource.getMessage(any(), anyOrNull(), any())).thenReturn("text")
    }

    @Test
    fun `requestVerification rejects a blocklisted email domain without touching the user record`() {
        assertThrows<BlockedEmailDomainException> {
            service.requestVerification(UUID.randomUUID(), "user@blocked.example")
        }
        verifyNoInteractions(userRepository, outboundChannel)
    }

    @Test
    fun `requestVerification is refused once the rate limit is hit within the window`() {
        primeSendingMocks()
        val userId = UUID.randomUUID()
        crypto.ensureUserKey(userId)
        val user = UserEntity().apply { id = userId }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        repeat(5) { service.requestVerification(userId, "user@example.com") }
        assertThrows<EmailVerificationRateLimitException> {
            service.requestVerification(userId, "user@example.com")
        }

        verify(outboundChannel, times(5)).send(any())
    }

    @Test
    fun `confirmVerification claims the account and re-runs the DEMO tier upgrade`() {
        val userId = UUID.randomUUID()
        val user = UserEntity().apply {
            id = userId
            emailVerificationTokenHash = CapabilityTokens.hash("tok")
            emailVerificationTokenExpiresAt = clock.instant().plusSeconds(60)
            emailHash = "hash"
        }
        whenever(userRepository.findByEmailVerificationTokenHash(CapabilityTokens.hash("tok"))).thenReturn(user)
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, "hash")).thenReturn(null)

        val result = service.confirmVerification("tok")

        assertTrue(result)
        assertTrue(user.claimed)
        verify(userSettingsService).upgradeToStandardOnClaim(userId)
    }

    @Test
    fun `confirmVerification does not claim or upgrade on an expired token`() {
        val userId = UUID.randomUUID()
        val user = UserEntity().apply {
            id = userId
            emailVerificationTokenHash = CapabilityTokens.hash("tok")
            emailVerificationTokenExpiresAt = clock.instant().minusSeconds(60)
        }
        whenever(userRepository.findByEmailVerificationTokenHash(CapabilityTokens.hash("tok"))).thenReturn(user)

        val result = service.confirmVerification("tok")

        assertFalse(result)
        verify(userSettingsService, never()).upgradeToStandardOnClaim(any())
    }
}
