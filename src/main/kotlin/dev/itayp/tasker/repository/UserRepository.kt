package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {

    fun findByTelegramId(telegramId: Long): UserEntity?

    fun findByEmailHash(emailHash: String): UserEntity?

    @Query("SELECT u FROM UserEntity u WHERE u.isDemo = true AND u.demoExpiresAt < :now")
    fun findExpiredDemoUsers(now: Instant): List<UserEntity>

    fun findByEmailVerificationToken(token: String): UserEntity?

    fun countByIsDemo(isDemo: Boolean): Long
}
