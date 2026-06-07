package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.EmailLoginTokenEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.EmailLoginTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Passwordless email login: a submitted address gets a single-use magic link; clicking it
 * proves ownership and logs the user in (registering on first use). Reuses the email-channel
 * and template machinery; identity resolution and the collision rules live in [UserAuthService].
 *
 * Tokens are stored hashed-by-handle only — the address itself is encrypted under the app KEK
 * via [UserCryptoService.encryptSystem] since no user (and thus no user DEK) exists yet.
 */
@Service
class EmailLoginService(
    private val tokenRepository: EmailLoginTokenRepository,
    private val userAuthService: UserAuthService,
    @Qualifier("authEmailChannel") private val outboundChannel: OutboundChannel,
    private val emailTemplateEngine: EmailTemplateEngine,
    private val messageSource: MessageSource,
    private val userCrypto: UserCryptoService,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(EmailLoginService::class.java)

    /**
     * Sends a login link for [email], unless the address has hit its recent-send rate limit.
     * Returns nothing and never reveals whether the address maps to an account (no enumeration).
     */
    @Transactional
    fun requestLogin(email: String) {
        val normalised = email.trim().lowercase()
        val emailHash = EmailHasher.hash(normalised)
        val now = clock.instant()

        val recent = tokenRepository.countByEmailHashAndCreatedAtAfter(emailHash, now.minus(RATE_WINDOW))
        if (recent >= RATE_LIMIT) {
            log.info("Email login rate-limited for emailHash={}", emailHash)
            return
        }

        val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").take(8)
        tokenRepository.save(EmailLoginTokenEntity().apply {
            this.token = token
            this.emailHash = emailHash
            this.emailEnc = userCrypto.encryptSystem(normalised)
            this.createdAt = now
            this.expiresAt = now.plus(TOKEN_TTL)
        })

        val loginUrl = "${appProperties.baseUrl}/api/auth/email/callback?token=$token"
        // Recipient locale is unknown before they have an account, so login emails default to English.
        val locale = Locale.ENGLISH
        val htmlBody = emailTemplateEngine.render(
            "emails/login-link.html",
            mapOf("login_url" to loginUrl, "email" to normalised),
            locale,
        )
        val subject = messageSource.getMessage("email.login.subject", null, locale)
        val textBody = messageSource.getMessage("email.login.textBody", arrayOf(loginUrl), locale)

        outboundChannel.send(
            EmailMessage(to = listOf(normalised), subject = subject, htmlBody = htmlBody, textBody = textBody),
        )
        log.info("Email login link sent for emailHash={}", emailHash)
    }

    /**
     * Consumes a magic-link token and resolves it to an account. Single-use: the token is
     * marked consumed before the account is resolved, so a replay is rejected.
     */
    @Transactional
    fun completeLogin(token: String): EmailLoginResult {
        val row = tokenRepository.findById(token).orElse(null)
            ?: return EmailLoginResult.Invalid
        val expiry = row.expiresAt ?: return EmailLoginResult.Invalid
        if (row.consumedAt != null || clock.instant().isAfter(expiry)) {
            return EmailLoginResult.Invalid
        }
        row.consumedAt = clock.instant()
        tokenRepository.save(row)

        val email = userCrypto.decryptSystem(row.emailEnc)
            ?: return EmailLoginResult.Invalid

        return when (val outcome = userAuthService.loginByEmail(email)) {
            is EmailLoginOutcome.Success -> EmailLoginResult.Success(outcome.user)
            EmailLoginOutcome.UnverifiedConflict -> EmailLoginResult.UnverifiedConflict
        }
    }

    /** Hourly housekeeping so expired/consumed tokens don't accumulate. */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    fun purgeExpiredTokens() {
        val removed = tokenRepository.deleteAllExpired(clock.instant())
        if (removed > 0) log.debug("Purged {} expired email login token(s)", removed)
    }

    companion object {
        private val TOKEN_TTL: Duration = Duration.ofMinutes(30)
        private val RATE_WINDOW: Duration = Duration.ofHours(1)
        private const val RATE_LIMIT = 5L
    }
}

/** Outcome of consuming a magic-link token. */
sealed interface EmailLoginResult {
    data class Success(val user: UserEntity) : EmailLoginResult
    data object UnverifiedConflict : EmailLoginResult
    data object Invalid : EmailLoginResult
}
