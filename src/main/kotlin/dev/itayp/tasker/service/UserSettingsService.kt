package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.LanguageOption
import dev.itayp.tasker.repository.UserSettingsRepository
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.util.UUID

@Service
class UserSettingsService(private val settingsRepository: UserSettingsRepository) {

    fun getOrCreate(userId: UUID): UserSettingsEntity =
        settingsRepository.findById(userId).orElseGet {
            settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
        }

    fun update(userId: UUID, request: UpdateUserSettingsRequest): UserSettingsEntity {
        require(request.timeZone in SUPPORTED_TIME_ZONES) { "Unsupported time zone: ${request.timeZone}" }
        require(SUPPORTED_LANGUAGES.any { it.code == request.preferredLanguage }) {
            "Unsupported language: ${request.preferredLanguage}"
        }
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

    companion object {
        val SUPPORTED_TIME_ZONES: List<String> = ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()

        val SUPPORTED_LANGUAGES: List<LanguageOption> = listOf(
            LanguageOption("ar", "Arabic"),
            LanguageOption("zh", "Chinese"),
            LanguageOption("nl", "Dutch"),
            LanguageOption("en", "English"),
            LanguageOption("fr", "French"),
            LanguageOption("de", "German"),
            LanguageOption("he", "Hebrew"),
            LanguageOption("it", "Italian"),
            LanguageOption("ja", "Japanese"),
            LanguageOption("ko", "Korean"),
            LanguageOption("pt", "Portuguese"),
            LanguageOption("ru", "Russian"),
            LanguageOption("es", "Spanish"),
        )
    }
}
