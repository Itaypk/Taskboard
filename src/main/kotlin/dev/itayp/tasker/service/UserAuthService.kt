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

        val newId = UUID.randomUUID()
        // DEK must exist before any sensitive field is encrypted under this user's id.
        userCrypto.ensureUserKey(newId)
        val created = UserEntity().apply {
            id = newId
            telegramId = data.telegramId
            telegramUsername = data.username
            telegramFirstName = userCrypto.encrypt(newId, data.firstName)
            telegramPhotoUrl = data.photoUrl
            createdAt = now
            lastLoginAt = now
        }
        val saved = userRepository.save(created)
        userService.initializeNewUser(saved.id!!)
        return saved
    }

    @Transactional
    fun ensureDevUser(userId: UUID, telegramId: Long): UserEntity {
        val existing = userRepository.findById(userId).orElse(null)
        if (existing != null) return existing

        userCrypto.ensureUserKey(userId)
        val now = clock.instant()
        val created = UserEntity().apply {
            this.id = userId
            this.telegramId = telegramId
            this.telegramFirstName = userCrypto.encrypt(userId, "Dev")
            this.createdAt = now
            this.lastLoginAt = now
        }
        val saved = userRepository.save(created)
        userService.initializeNewUser(saved.id!!)
        logger.debug("Created dev user with id $userId and telegram id $telegramId")
        return saved
    }

    @Transactional
    fun createDemoUser(ttlHours: Long = 24): UserEntity {
        val now = clock.instant()
        val newId = UUID.randomUUID()
        userCrypto.ensureUserKey(newId)
        val user = UserEntity().apply {
            id = newId
            isDemo = true
            demoExpiresAt = now.plus(Duration.ofHours(ttlHours))
            createdAt = now
            lastLoginAt = now
        }
        val saved = userRepository.save(user)
        userService.initializeNewUser(saved.id!!)
        demoDataSeeder.seed(saved.id!!)
        logger.info("Created demo user ${saved.id}, expires at ${saved.demoExpiresAt}")
        return saved
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserAuthService::class.java)
    }
}
