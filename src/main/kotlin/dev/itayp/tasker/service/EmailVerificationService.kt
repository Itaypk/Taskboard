package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.Duration
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import java.util.UUID

@Service
@EnableConfigurationProperties(AppProperties::class)
class EmailVerificationService(
    private val userRepository: UserRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    @Qualifier("authEmailChannel") private val outboundChannel: OutboundChannel,
    private val appProperties: AppProperties,
    private val clock: Clock,
    private val emailTemplateEngine: EmailTemplateEngine,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val userCrypto: UserCryptoService,
    private val emailDomainBlocklistService: EmailDomainBlocklistService,
    @Qualifier("emailVerificationRateLimiter") private val rateLimiter: RateLimiter,
) {

    private val log = LoggerFactory.getLogger(EmailVerificationService::class.java)

    fun requestVerification(userId: UUID, email: String) {
        val normalised = email.trim().lowercase()
        emailDomainBlocklistService.requireAllowed(normalised)
        if (!rateLimiter.tryConsume(userId.toString())) {
            throw EmailVerificationRateLimitException()
        }
        val emailHash = EmailHasher.hash(normalised)
        // An address can back at most one account (unique email_hash). Refuse rather than let the
        // unique constraint surface as a 500 — and don't let one user claim another's address.
        val owner = userRepository.findByEmailHash(emailHash)
        if (owner != null && owner.id != userId) {
            throw EmailAlreadyLinkedException()
        }
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }

        val now = clock.instant()
        val token = UUID.randomUUID().toString().replace("-", "")
        user.email = userCrypto.encrypt(userId, normalised)
        user.emailHash = emailHash
        user.emailVerifiedAt = null
        user.emailVerificationToken = token
        user.emailVerificationTokenExpiresAt = now.plus(Duration.ofHours(24))
        userRepository.save(user)

        // Link opens a side-effect-free SPA confirm page; the actual verification happens on POST.
        val verifyUrl = "${appProperties.baseUrl}/email-verify?token=$token"
        
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
    }

    fun confirmVerification(token: String): Boolean {
        val user = userRepository.findByEmailVerificationToken(token) ?: return false
        val expiry = user.emailVerificationTokenExpiresAt ?: return false
        if (clock.instant().isAfter(expiry)) return false

        val now = clock.instant()
        user.emailVerifiedAt = now
        user.emailVerificationToken = null
        user.emailVerificationTokenExpiresAt = null
        // A verified email is a login method, so it claims the account (one-way latch).
        user.claimed = true
        userRepository.save(user)
        // No-op unless the account was still on the DEMO tier — upgrades it to STANDARD if the cap
        // allows, otherwise leaves it on its existing DEMO budget.
        userSettingsService.upgradeToStandardOnClaim(user.id!!)

        // A verified email is also a login method: attach an email identity so the address can be
        // used for passwordless login (and shows up as a connected account).
        val emailHash = user.emailHash
        if (emailHash != null &&
            authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, emailHash) == null
        ) {
            authIdentityRepository.save(AuthIdentityEntity().apply {
                id = Uuid.generateV7().toJavaUuid()
                userId = user.id
                provider = AuthProvider.EMAIL
                providerUserId = emailHash
                verifiedAt = now
                createdAt = now
                lastLoginAt = now
            })
        }

        log.info("Email verified for userId={}", user.id)
        return true
    }
}

/** The submitted address already backs a different account. */
class EmailAlreadyLinkedException : RuntimeException("Email already linked to another account")

/** Thrown when verification-email requests exceed the rate limit. Maps to HTTP 429. */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class EmailVerificationRateLimitException : RuntimeException("Too many verification emails sent; try again later")
