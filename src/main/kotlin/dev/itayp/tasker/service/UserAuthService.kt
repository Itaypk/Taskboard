package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class UserAuthService(
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val demoDataSeeder: DemoDataSeeder,
    private val userCrypto: UserCryptoService,
    private val clock: Clock,
) {

    @Transactional
    fun loginOrRegisterByTelegram(data: TelegramAuthData): UserEntity {
        val now = clock.instant()
        val existing = userRepository.findByTelegramId(data.telegramId)
        if (existing != null) {
            existing.telegramUsername = data.username
            existing.telegramFirstName = userCrypto.encrypt(existing.id!!, data.firstName)
            existing.telegramPhotoUrl = data.photoUrl
            existing.lastLoginAt = now
            return userRepository.save(existing)
        }

        // Persist the user row first so the user_data_key FK (and any other FK to users)
        // is satisfied when ensureUserKey writes the wrapped DEK.
        val newId = UUID.randomUUID()
        val draft = UserEntity().apply {
            id = newId
            telegramId = data.telegramId
            telegramUsername = data.username
            telegramPhotoUrl = data.photoUrl
            createdAt = now
            lastLoginAt = now
        }
        userRepository.save(draft)
        userCrypto.ensureUserKey(newId)
        draft.telegramFirstName = userCrypto.encrypt(newId, data.firstName)
        val saved = userRepository.save(draft)
        userService.initializeNewUser(saved.id!!)
        return saved
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
        logger.info("Created demo user $newId, expires at ${draft.demoExpiresAt}")
        return draft
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserAuthService::class.java)
    }
}
