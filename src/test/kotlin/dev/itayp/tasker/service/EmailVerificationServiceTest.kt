package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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

    private val service = EmailVerificationService(
        userRepository, authIdentityRepository, outboundChannel, AppProperties(baseUrl = "https://test.local"),
        clock, emailTemplateEngine, userSettingsService, messageSource, crypto, blocklist,
    )

    @Test
    fun `requestVerification rejects a blocklisted email domain without touching the user record`() {
        assertThrows<BlockedEmailDomainException> {
            service.requestVerification(UUID.randomUUID(), "user@blocked.example")
        }
        verifyNoInteractions(userRepository, outboundChannel)
    }
}
