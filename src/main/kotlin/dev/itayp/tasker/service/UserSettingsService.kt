package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.GenderOption
import dev.itayp.tasker.model.response.LanguageOption
import dev.itayp.tasker.repository.UserSettingsRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.UUID

data class UserPlanningScheduleChangedEvent(val userId: UUID)

@Service
class UserSettingsService(
    private val settingsRepository: UserSettingsRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun getOrCreate(userId: UUID): UserSettingsEntity =
        settingsRepository.findById(userId).orElseGet {
            settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
        }

    fun update(userId: UUID, request: UpdateUserSettingsRequest): UserSettingsEntity {
        require(request.timeZone in SUPPORTED_TIME_ZONES) { "Unsupported time zone: ${request.timeZone}" }
        require(SUPPORTED_LANGUAGES.any { it.code == request.preferredLanguage }) {
            "Unsupported language: ${request.preferredLanguage}"
        }
        request.gender?.let {
            require(SUPPORTED_GENDERS.any { g -> g.code == it }) { "Unsupported gender: $it" }
        }
        request.planningCron?.let {
            require(CronExpression.isValidExpression(it)) { "Invalid cron expression: $it" }
        }
        request.weekStartDay?.let {
            require(runCatching { DayOfWeek.valueOf(it) }.isSuccess) { "Unsupported week start day: $it" }
        }
        val entity = getOrCreate(userId)
        val scheduleChanged = entity.planningCron != request.planningCron ||
            entity.timeZone != request.timeZone
        entity.displayName = request.displayName
        entity.contextBlock = request.contextBlock
        entity.timeZone = request.timeZone
        entity.preferredLanguage = request.preferredLanguage
        entity.calendarInviteEmail = request.calendarInviteEmail
        entity.gender = request.gender
        entity.agentDescription = request.agentDescription
        entity.planningCron = request.planningCron
        entity.weekStartDay = request.weekStartDay
        val saved = settingsRepository.save(entity)
        if (scheduleChanged) {
            eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
        }
        return saved
    }

    fun initializeForNewUser(userId: UUID) {
        settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
    }

    companion object {
        val SUPPORTED_TIME_ZONES: List<String> = (
            ZoneId.getAvailableZoneIds()
                .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") } +
            listOf("UTC")
        ).sorted()

        val SUPPORTED_GENDERS: List<GenderOption> = listOf(
            GenderOption("masculine", "Masculine"),
            GenderOption("feminine", "Feminine"),
            GenderOption("neutral", "Neutral"),
        )

        val SUPPORTED_LANGUAGES: List<LanguageOption> = listOf(
            LanguageOption("ar", "Arabic"),
            LanguageOption("zh", "Chinese"),
            LanguageOption("nl", "Dutch"),
            LanguageOption("en-GB", "English (UK)"),
            LanguageOption("en-US", "English (US)"),
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
