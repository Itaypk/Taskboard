package dev.itayp.tasker.service

import dev.itayp.nescioquid.telegram.TelegramAuthData
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.google.GoogleAuthData
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class UserAuthService(
    private val userRepository: UserRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    private val userService: UserService,
    private val tutorialSeeder: TutorialSeeder,
    private val userCrypto: UserCryptoService,
    private val appProperties: AppProperties,
    private val registrationPolicy: RegistrationPolicy,
    private val clock: Clock,
) {

    /**
     * Generic login-or-register over an external identity. Resolves `(provider, providerUserId)`
     * through [AuthIdentityRepository]; on a hit it logs the existing user in, otherwise it
     * provisions a fresh user, encryption key, default data, and the identity row.
     *
     * Profile mutation is left to the caller via [onExisting] / [onCreate] so each provider owns
     * its own user fields; this method only handles identity resolution and the create lifecycle.
     */
    @Transactional
    fun loginOrRegister(
        provider: String,
        providerUserId: String,
        verified: Boolean,
        onExisting: (UserEntity) -> Unit,
        onCreate: (UserEntity) -> Unit,
        hints: RegistrationHints = RegistrationHints.NONE,
    ): UserEntity {
        val now = clock.instant()
        val identity = authIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
        if (identity != null) {
            val user = userRepository.findById(identity.userId!!).orElseThrow {
                IllegalStateException("Auth identity ${identity.id} references missing user ${identity.userId}")
            }
            identity.lastLoginAt = now
            if (verified && identity.verifiedAt == null) identity.verifiedAt = now
            authIdentityRepository.save(identity)
            user.lastLoginAt = now
            onExisting(user)
            logger.debug("Logged in existing user {} via {}", user.id, provider)
            return userRepository.save(user)
        }

        registrationPolicy.requireNewAccountAllowed(provider)

        // New identity -> new user. Persist the user row first so the user_data_key FK (and the
        // auth_identities FK) is satisfied when ensureUserKey / attachIdentity write their rows.
        val newId = UUID.randomUUID()
        val draft = UserEntity().apply {
            id = newId
            createdAt = now
            lastLoginAt = now
            lastActiveAt = now
            // Registering through an external identity means the account is claimed from birth.
            claimed = true
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(newId)
        onCreate(draft)
        val saved = userRepository.save(draft)
        attachIdentity(newId, provider, providerUserId, verified, now)
        userService.initializeNewUser(saved.id!!, hints, claimed = true)
        logger.info("Registered new user {} via {}", saved.id, provider)
        return saved
    }

    @Transactional
    fun loginOrRegisterByTelegram(data: TelegramAuthData, hints: RegistrationHints = RegistrationHints.NONE): UserEntity =
        loginOrRegister(
            provider = AuthProvider.TELEGRAM,
            providerUserId = data.telegramId.toString(),
            verified = true,
            onExisting = { user -> applyTelegramProfile(user, data) },
            onCreate = { user -> applyTelegramProfile(user, data) },
            hints = hints,
        )

    private fun applyTelegramProfile(user: UserEntity, data: TelegramAuthData) {
        user.telegramId = data.telegramId
        user.telegramUsername = data.username
        user.telegramPhotoUrl = data.photoUrl
        user.telegramFirstName = userCrypto.encrypt(user.id!!, data.firstName)
    }

    /**
     * Logs in (or registers) by a verified email address, applying the collision rules:
     *  - a user already owns this *verified* email -> log into that account (ensure an email identity);
     *  - a user owns this email, but it's *unverified* -> [EmailLoginOutcome.UnverifiedConflict]
     *    (don't auto-merge; they should sign in with their existing method and verify in settings);
     *  - nobody owns it -> register a fresh account with a verified email identity.
     *
     * The caller is responsible for having proven ownership of [email] (the magic-link click).
     */
    @Transactional
    fun loginByEmail(email: String, hints: RegistrationHints = RegistrationHints.NONE): EmailLoginOutcome {
        val normalised = email.trim().lowercase()
        val emailHash = EmailHasher.hash(normalised)
        val existing = userRepository.findByEmailHash(emailHash)
        if (existing != null) {
            if (existing.emailVerifiedAt == null) {
                logger.info("Email login refused: address belongs to an unverified account {}", existing.id)
                return EmailLoginOutcome.UnverifiedConflict
            }
            // Verified owner: ensure the email identity exists (e.g. legacy rows), then log in.
            val now = clock.instant()
            attachIdentityIfMissing(existing.id!!, AuthProvider.EMAIL, emailHash, verified = true, now = now)
            existing.claimed = true
            existing.lastLoginAt = now
            logger.debug("Logged in existing user {} via email", existing.id)
            return EmailLoginOutcome.Success(userRepository.save(existing))
        }

        val user = loginOrRegister(
            provider = AuthProvider.EMAIL,
            providerUserId = emailHash,
            verified = true,
            onExisting = { /* no email identity can exist here: findByEmailHash was null */ },
            onCreate = { u ->
                u.email = userCrypto.encrypt(u.id!!, normalised)
                u.emailHash = emailHash
                u.emailVerifiedAt = clock.instant()
            },
            hints = hints,
        )
        return EmailLoginOutcome.Success(user)
    }

    /**
     * Logs in (or registers) with a validated Google id_token:
     *  - a known Google account (`sub`) -> log into its user;
     *  - a new one whose email an account already holds -> sign into that account and attach the
     *    Google identity, but only if the account verified the address *and* Google's claim is
     *    trustworthy ([GoogleAuthData.trustedEmail]); otherwise [GoogleLoginOutcome.EmailConflict];
     *  - nobody holds the email -> register, storing a trusted email as the account's verified
     *    address (and so as an email login method, as verifying in Settings would).
     */
    @Transactional
    fun loginByGoogle(data: GoogleAuthData, hints: RegistrationHints = RegistrationHints.NONE): GoogleLoginOutcome {
        if (authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, data.subject) != null) {
            return GoogleLoginOutcome.Success(loginOrRegister(AuthProvider.GOOGLE, data.subject, true, {}, {}, hints))
        }

        val trustedEmail = data.trustedEmail
        val anyEmail = data.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val owner = anyEmail?.let { userRepository.findByEmailHash(EmailHasher.hash(it)) }
        if (owner != null) {
            val ownerHasGoogle = authIdentityRepository.findAllByUserId(owner.id!!).any { it.provider == AuthProvider.GOOGLE }
            if (trustedEmail == null || owner.emailVerifiedAt == null || ownerHasGoogle) {
                logger.info("Google login refused: its email belongs to account {} and can't be matched safely", owner.id)
                return GoogleLoginOutcome.EmailConflict
            }
            val now = clock.instant()
            attachIdentity(owner.id!!, AuthProvider.GOOGLE, data.subject, verified = true, now = now)
            attachIdentityIfMissing(owner.id!!, AuthProvider.EMAIL, owner.emailHash!!, verified = true, now = now)
            owner.claimed = true
            owner.lastLoginAt = now
            logger.info("Linked Google identity to user {} by verified email", owner.id)
            return GoogleLoginOutcome.Success(userRepository.save(owner))
        }

        val emailHash = trustedEmail?.let(EmailHasher::hash)
        val user = loginOrRegister(
            provider = AuthProvider.GOOGLE,
            providerUserId = data.subject,
            verified = true,
            onExisting = { /* unreachable: the identity lookup above was empty */ },
            onCreate = { u ->
                if (trustedEmail != null) {
                    u.email = userCrypto.encrypt(u.id!!, trustedEmail)
                    u.emailHash = emailHash
                    u.emailVerifiedAt = clock.instant()
                }
            },
            hints = hints,
        )
        if (emailHash != null) attachIdentityIfMissing(user.id!!, AuthProvider.EMAIL, emailHash, verified = true, now = clock.instant())
        return GoogleLoginOutcome.Success(user)
    }

    /** Whether an account already holds [emailHash] — lets a closed instance skip mailing strangers. */
    fun hasAccountForEmail(emailHash: String): Boolean = userRepository.findByEmailHash(emailHash) != null

    private fun attachIdentityIfMissing(userId: UUID, provider: String, providerUserId: String, verified: Boolean, now: Instant) {
        if (authIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId) == null) {
            attachIdentity(userId, provider, providerUserId, verified, now)
        }
    }

    @Transactional
    fun ensureDevUser(userId: UUID, telegramId: Long): UserEntity {
        val existing = userRepository.findById(userId).orElse(null)
        if (existing != null) return existing

        val now = clock.instant()
        val draft = UserEntity().apply {
            this.id = userId
            this.telegramId = telegramId
            this.createdAt = now
            this.lastLoginAt = now
            this.lastActiveAt = now
            this.claimed = true
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(userId)
        draft.telegramFirstName = userCrypto.encrypt(userId, "Dev")
        val saved = userRepository.save(draft)
        // Attach the Telegram identity too, so a Telegram login with this id resolves to the dev
        // user instead of colliding on the users.telegram_id unique constraint.
        attachIdentity(userId, AuthProvider.TELEGRAM, telegramId.toString(), verified = true, now = now)
        userService.initializeNewUser(saved.id!!, claimed = true)
        logger.debug("Created dev user with id $userId and telegram id $telegramId")
        return saved
    }

    /**
     * Zero-registration start: a **real but unclaimed** account (no login identity yet). It behaves
     * like any account except that it's eligible for inactivity-based reclamation until the user
     * claims it by linking an identity / verifying an email. Seeded with a tutorial backlog rather
     * than throwaway sample data. See docs/DEMO-ACCOUNT-UNIFICATION.md.
     */
    @Transactional
    fun createUnclaimedUser(hints: RegistrationHints = RegistrationHints.NONE): UserEntity {
        registrationPolicy.requireDemoAvailable()
        val unclaimedCount = userRepository.countByClaimed(false)
        if (unclaimedCount >= appProperties.unclaimedAccountCap) {
            logger.warn("Unclaimed account cap reached ({}); refusing new unclaimed signup", unclaimedCount)
            throw UnclaimedAccountCapExceededException()
        }

        val now = clock.instant()
        val newId = UUID.randomUUID()
        val draft = UserEntity().apply {
            id = newId
            claimed = false
            createdAt = now
            lastLoginAt = now
            lastActiveAt = now
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(newId)
        // Unclaimed/demo accounts always get AI access (on a bounded AiTier.DEMO budget) — the demo
        // is the marketing funnel and must never hit the granted-tier cap (docs/DEMO-ACCOUNT-UNIFICATION.md).
        userService.initializeNewUser(newId, hints, claimed = false)
        tutorialSeeder.seed(newId, hints.language)
        // No auth identity yet: the account is unclaimed until the user links a login method.
        logger.info("Created unclaimed user $newId")
        return draft
    }

    private fun attachIdentity(userId: UUID, provider: String, providerUserId: String, verified: Boolean, now: Instant) {
        authIdentityRepository.save(AuthIdentityEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = userId
            this.provider = provider
            this.providerUserId = providerUserId
            this.verifiedAt = if (verified) now else null
            this.createdAt = now
            this.lastLoginAt = now
        })
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserAuthService::class.java)
    }
}

/** The unclaimed-account surge cap ([AppProperties.unclaimedAccountCap]) is currently full. Maps to HTTP 503. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
class UnclaimedAccountCapExceededException :
    RuntimeException("Sign-ups are temporarily unavailable, please try again later")
