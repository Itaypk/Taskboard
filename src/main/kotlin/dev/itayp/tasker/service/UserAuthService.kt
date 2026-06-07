package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class UserAuthService(
    private val userRepository: UserRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    private val userService: UserService,
    private val demoDataSeeder: DemoDataSeeder,
    private val userCrypto: UserCryptoService,
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

        // New identity -> new user. Persist the user row first so the user_data_key FK (and the
        // auth_identities FK) is satisfied when ensureUserKey / attachIdentity write their rows.
        val newId = UUID.randomUUID()
        val draft = UserEntity().apply {
            id = newId
            createdAt = now
            lastLoginAt = now
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(newId)
        onCreate(draft)
        val saved = userRepository.save(draft)
        attachIdentity(newId, provider, providerUserId, verified, now)
        userService.initializeNewUser(saved.id!!)
        logger.info("Registered new user {} via {}", saved.id, provider)
        return saved
    }

    @Transactional
    fun loginOrRegisterByTelegram(data: TelegramAuthData): UserEntity =
        loginOrRegister(
            provider = AuthProvider.TELEGRAM,
            providerUserId = data.telegramId.toString(),
            verified = true,
            onExisting = { user -> applyTelegramProfile(user, data) },
            onCreate = { user -> applyTelegramProfile(user, data) },
        )

    private fun applyTelegramProfile(user: UserEntity, data: TelegramAuthData) {
        user.telegramId = data.telegramId
        user.telegramUsername = data.username
        user.telegramPhotoUrl = data.photoUrl
        user.telegramFirstName = userCrypto.encrypt(user.id!!, data.firstName)
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
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(userId)
        draft.telegramFirstName = userCrypto.encrypt(userId, "Dev")
        val saved = userRepository.save(draft)
        // Attach the Telegram identity too, so a Telegram login with this id resolves to the dev
        // user instead of colliding on the users.telegram_id unique constraint.
        attachIdentity(userId, AuthProvider.TELEGRAM, telegramId.toString(), verified = true, now = now)
        userService.initializeNewUser(saved.id!!)
        logger.debug("Created dev user with id $userId and telegram id $telegramId")
        return saved
    }

    @Transactional
    fun createDemoUser(ttlHours: Long = 24): UserEntity {
        val now = clock.instant()
        val newId = UUID.randomUUID()
        val draft = UserEntity().apply {
            id = newId
            isDemo = true
            demoExpiresAt = now.plus(Duration.ofHours(ttlHours))
            createdAt = now
            lastLoginAt = now
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(newId)
        userService.initializeNewUser(newId)
        demoDataSeeder.seed(newId)
        // Demo users are deliberately channel-less: no auth identity, no login method.
        logger.info("Created demo user $newId, expires at ${draft.demoExpiresAt}")
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
