package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import java.util.logging.Logger

@Service
class UserAuthService(
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val clock: Clock,
) {

    @Transactional
    fun loginOrRegisterByTelegram(data: TelegramAuthData): UserEntity {
        val now = clock.instant()
        val existing = userRepository.findByTelegramId(data.telegramId)
        if (existing != null) {
            existing.telegramUsername = data.username
            existing.telegramFirstName = data.firstName
            existing.telegramPhotoUrl = data.photoUrl
            existing.lastLoginAt = now
            return userRepository.save(existing)
        }

        val created = UserEntity().apply {
            id = UUID.randomUUID()
            telegramId = data.telegramId
            telegramUsername = data.username
            telegramFirstName = data.firstName
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

        val now = clock.instant()
        val created = UserEntity().apply {
            this.id = userId
            this.telegramId = telegramId
            this.telegramFirstName = "Dev"
            this.createdAt = now
            this.lastLoginAt = now
        }
        val saved = userRepository.save(created)
        userService.initializeNewUser(saved.id!!)
        logger.info("Created dev user with id $userId and telegram id $telegramId")
        return saved
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserAuthService::class.java)
    }
}
