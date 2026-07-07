package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.model.response.GenderOption
import dev.itayp.tasker.model.response.LanguageOption
import dev.itayp.tasker.repository.UserSettingsRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

data class UserPlanningScheduleChangedEvent(val userId: UUID)

/** Outcome of [UserSettingsService.appendToContextBlock]. */
enum class AppendContextResult {
    /** The addition was appended to the context block. */
    APPENDED,

    /** The addition would overflow the context-block cap; nothing was written. */
    FULL,

    /** The addition was blank; nothing was written. */
    NO_OP,
}

@Service
class UserSettingsService(
    private val settingsRepository: UserSettingsRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val userCrypto: UserCryptoService,
) {

    fun getOrCreate(userId: UUID): UserSettings = toDomain(userId, fetchOrCreate(userId))

    /**
     * Appends a single line to the user's (user-authored) context block, separated from any existing
     * content by a blank line. Backs the assistant's end-of-session "want me to remember this?" flow —
     * the user is always the one who accepts before we call this. Enforces the same
     * [CONTEXT_BLOCK_MAX_CHARS] soft cap as the settings form: if the addition wouldn't fit, nothing is
     * written and [AppendContextResult.FULL] is returned so the caller can tell the user to trim it in
     * Settings first. A blank addition is a no-op.
     */
    fun appendToContextBlock(userId: UUID, addition: String): AppendContextResult {
        val trimmed = addition.trim()
        if (trimmed.isEmpty()) return AppendContextResult.NO_OP
        val entity = fetchOrCreate(userId)
        val existing = userCrypto.decrypt(userId, entity.contextBlock)?.trimEnd().orEmpty()
        val combined = if (existing.isEmpty()) trimmed else "$existing\n\n$trimmed"
        if (combined.length > CONTEXT_BLOCK_MAX_CHARS) return AppendContextResult.FULL
        entity.contextBlock = userCrypto.encrypt(userId, combined)
        settingsRepository.save(entity)
        return AppendContextResult.APPENDED
    }

    fun update(userId: UUID, request: UpdateUserSettingsRequest): UserSettings {
        validateSettingsInput(
            timeZone = request.timeZone,
            preferredLanguage = request.preferredLanguage,
            gender = request.gender,
            planningCron = request.planningCron,
            weekStartDay = request.weekStartDay,
        )
        val entity = fetchOrCreate(userId)
        val scheduleChanged = entity.planningCron != request.planningCron ||
            entity.timeZone != request.timeZone
        entity.displayName = userCrypto.encrypt(userId, request.displayName)
        entity.contextBlock = userCrypto.encrypt(userId, request.contextBlock)
        entity.timeZone = request.timeZone
        entity.preferredLanguage = request.preferredLanguage
        entity.calendarInviteEmail = request.calendarInviteEmail
        entity.appReminders = request.appReminders
        entity.gender = request.gender
        entity.agentDescription = userCrypto.encrypt(userId, request.agentDescription)
        entity.planningCron = request.planningCron
        entity.weekStartDay = request.weekStartDay
        entity.autoArchiveDays = request.autoArchiveDays
        entity.aiEnabled = request.aiEnabled
        entity.aiEnhancedReminders = request.aiEnhancedReminders
        val saved = settingsRepository.save(entity)
        if (scheduleChanged) {
            eventPublisher.publishEvent(UserPlanningScheduleChangedEvent(userId))
        }
        return toDomain(userId, saved)
    }

    /**
     * `findAllByPlanningCronIsNotNull` returns raw entities for the scheduler; only the
     * non-sensitive scheduling fields (`planningCron`, `timeZone`, `weekStartDay`, `userId`)
     * are read. Sensitive fields stay as ciphertext on the returned entities — do not
     * surface them to callers without going through this service's domain mappers.
     */
    fun findAllWithPlanningCron(): List<UserSettingsEntity> =
        settingsRepository.findAllByPlanningCronIsNotNull()

    fun findAllWithAutoArchive(): List<UserSettingsEntity> =
        settingsRepository.findAllByAutoArchiveDaysIsNotNull()

    fun getLocale(userId: UUID): Locale = toLocale(fetchOrCreate(userId).preferredLanguage)

    /** Maps a stored `preferredLanguage` tag to a [Locale]. Centralized so callers that already hold
     * a [UserSettings] don't re-fetch (and so the tag→locale rule lives in one place). */
    fun toLocale(preferredLanguage: String): Locale = Locale.forLanguageTag(preferredLanguage)

    fun initializeForNewUser(userId: UUID) {
        settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
    }

    private fun fetchOrCreate(userId: UUID): UserSettingsEntity =
        settingsRepository.findById(userId).orElseGet {
            settingsRepository.save(UserSettingsEntity().apply { this.userId = userId })
        }

    /**
     * Shared enum/regex checks for the user-supplied subset of settings. Throws
     * [IllegalArgumentException] (which propagates as 400 / rolls back any
     * surrounding `@Transactional`) on any invalid value. Used by both the normal
     * settings-update endpoint and the account-import flow.
     */
    fun validateSettingsInput(
        timeZone: String,
        preferredLanguage: String,
        gender: String?,
        planningCron: String?,
        weekStartDay: String?,
    ) {
        require(timeZone in SUPPORTED_TIME_ZONES) { "Unsupported time zone: $timeZone" }
        require(SUPPORTED_LANGUAGES.any { it.code == preferredLanguage }) {
            "Unsupported language: $preferredLanguage"
        }
        gender?.let {
            require(SUPPORTED_GENDERS.any { g -> g.code == it }) { "Unsupported gender: $it" }
        }
        planningCron?.let {
            require(CronExpression.isValidExpression(it)) { "Invalid cron expression: $it" }
        }
        weekStartDay?.let {
            require(runCatching { DayOfWeek.valueOf(it) }.isSuccess) { "Unsupported week start day: $it" }
        }
    }

    private fun toDomain(userId: UUID, entity: UserSettingsEntity): UserSettings =
        UserSettings(
            userId = userId,
            displayName = userCrypto.decrypt(userId, entity.displayName),
            contextBlock = userCrypto.decrypt(userId, entity.contextBlock),
            timeZone = entity.timeZone,
            preferredLanguage = entity.preferredLanguage,
            calendarInviteEmail = entity.calendarInviteEmail,
            appReminders = entity.appReminders,
            gender = entity.gender,
            agentDescription = userCrypto.decrypt(userId, entity.agentDescription),
            planningCron = entity.planningCron,
            weekStartDay = entity.weekStartDay,
            autoArchiveDays = entity.autoArchiveDays,
            aiEnabled = entity.aiEnabled,
            aiEnhancedReminders = entity.aiEnhancedReminders,
            aiTier = entity.aiTier,
        )

    companion object {
        /** Soft cap on the user context block, mirroring the settings form's `@Size(max)` on `contextBlock`. */
        const val CONTEXT_BLOCK_MAX_CHARS = 4000

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
