package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.repository.UserSettingsRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserSettingsService(private val settingsRepository: UserSettingsRepository) {

    fun getOrCreate(userId: UUID): UserSettingsEntity =
        settingsRepository.findById(userId).orElseGet {
            settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
        }

    fun update(userId: UUID, request: UpdateUserSettingsRequest): UserSettingsEntity {
        val entity = getOrCreate(userId)
        entity.displayName = request.displayName
        entity.contextBlock = request.contextBlock
        entity.timeZone = request.timeZone
        entity.preferredLanguage = request.preferredLanguage
        return settingsRepository.save(entity)
    }

    fun initializeForNewUser(userId: UUID) {
        settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
    }
}
