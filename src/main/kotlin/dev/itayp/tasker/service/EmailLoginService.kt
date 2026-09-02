package dev.itayp.tasker.service

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.EmailLoginTokenEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.EmailLoginTokenRepository
import dev.itayp.tasker.util.localRedirect
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
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
    private val emailDomainBlocklistService: EmailDomainBlocklistService,
    private val localeNegotiationService: LocaleNegotiationService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(EmailLoginService::class.java)

    /**
     * Sends a login link for [email]. A blocklisted domain throws [BlockedEmailDomainException]
     * (a 400 the form can show); hitting the recent-send rate limit returns silently. The silent
     * case is what preserves the no-enumeration guarantee — nothing here ever reveals whether the
     * address maps to an account.
     *
     * [next] is an optional same-origin path to navigate to after login (e.g. a board invitation
     * accept page). It is validated and threaded through the confirm-page URL so the SPA can
     * redirect there on success.
     */
    @Transactional
    fun requestLogin(email: String, next: String? = null, acceptLanguage: String? = null) {
        val normalised = email.trim().lowercase()
        // Rejected loudly, unlike the rate limit below. Whether a domain is disposable is not
        // account-specific, so saying so leaks nothing about whether an account exists — and with
        // ~75k bundled domains, a silent no-op would make a false positive look like mail that
        // never arrived. The per-address rate limit stays silent; that one *is* account-adjacent.
        emailDomainBlocklistService.requireAllowed(normalised)
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

        // The link opens a side-effect-free SPA confirm page; the actual session is created on POST.
        val safeNext = localRedirect(next).takeIf { it != "/" || next == "/" }
        val loginUrl = buildString {
            append("${appProperties.baseUrl}/email-login?token=$token")
            if (safeNext != null && safeNext != "/") {
                append("&next=${URLEncoder.encode(safeNext, Charsets.UTF_8)}")
            }
        }
        // No stored preference exists before the account does, so the pre-auth login email is
        // localized from the request's Accept-Language (best supported match, else English) — see
        // docs/I18N.md, D2. This closes the small gap where a Hebrew visitor got an English link.
        val locale = localeNegotiationService.resolveLocale(acceptLanguage)
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
     * Side-effect-free validity check so the confirm page can show an early error before the user
     * clicks. Does not consume or modify the token in any way — safe for scanner prefetches.
     */
    @Transactional(readOnly = true)
    fun precheckToken(token: String): Boolean {
        val row = tokenRepository.findById(token).orElse(null) ?: return false
        val expiry = row.expiresAt ?: return false
        return row.consumedAt == null && !clock.instant().isAfter(expiry)
    }

    /**
     * Consumes a magic-link token and resolves it to an account. Single-use: the token is
     * marked consumed before the account is resolved, so a replay is rejected.
     */
    @Transactional
    fun completeLogin(token: String, acceptLanguage: String? = null): EmailLoginResult {
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

        // On first use this registers the account; seed its language from the (browser) request that
        // clicked the link. An existing account keeps its stored preference (hint is ignored there).
        val localeHint = localeNegotiationService.resolveSupportedTag(acceptLanguage)
        return when (val outcome = userAuthService.loginByEmail(email, localeHint)) {
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
