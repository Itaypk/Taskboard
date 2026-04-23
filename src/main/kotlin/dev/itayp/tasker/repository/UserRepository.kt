package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {

    fun findByTelegramId(telegramId: Long): UserEntity?
}
