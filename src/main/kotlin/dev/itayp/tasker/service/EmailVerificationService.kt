package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.web.util.HtmlUtils
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import org.springframework.context.MessageSource
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
@EnableConfigurationProperties(AppProperties::class)
class EmailVerificationService(
    private val userRepository: UserRepository,
    private val outboundChannel: OutboundChannel,
    private val appProperties: AppProperties,
    private val clock: Clock,
    private val emailTemplateEngine: EmailTemplateEngine,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val userCrypto: UserCryptoService,
) {

    private val log = LoggerFactory.getLogger(EmailVerificationService::class.java)

    fun requestVerification(userId: UUID, email: String) {
        val normalised = email.trim().lowercase()
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }
        val token = UUID.randomUUID().toString().replace("-", "")
        user.email = userCrypto.encrypt(userId, normalised)
        user.emailHash = sha256Hex(normalised)
        user.emailVerifiedAt = null
        user.emailVerificationToken = token
        user.emailVerificationTokenExpiresAt = clock.instant().plus(Duration.ofHours(24))
        userRepository.save(user)

        val verifyUrl = "${appProperties.baseUrl}/api/v1/settings/email/verify?token=$token"
        
        val locale = userSettingsService.getLocale(userId)
        val htmlBody = emailTemplateEngine.render(
            "emails/verify-email.html",
            mapOf("verify_url" to verifyUrl, "email" to normalised),
            locale
        )
        val subject = messageSource.getMessage("email.verify.subject", null, locale)
        val textBody = messageSource.getMessage("email.verify.textBody", arrayOf(verifyUrl), locale)

        outboundChannel.send(
            EmailMessage(
                to = listOf(normalised),
                subject = subject,
                htmlBody = htmlBody,
                textBody = textBody,
            )
        )

        log.info("Email verification requested for userId={}", userId)
        log.debug("Email verification email value: {}", normalised)
    }

    fun confirmVerification(token: String): Boolean {
        val user = userRepository.findByEmailVerificationToken(token) ?: return false
        val expiry = user.emailVerificationTokenExpiresAt ?: return false
        if (clock.instant().isAfter(expiry)) return false

        user.emailVerifiedAt = clock.instant()
        user.emailVerificationToken = null
        user.emailVerificationTokenExpiresAt = null
        userRepository.save(user)

        log.info("Email verified for userId={}", user.id)
        return true
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
