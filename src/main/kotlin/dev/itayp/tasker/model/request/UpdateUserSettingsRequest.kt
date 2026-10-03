package dev.itayp.tasker.model.request

import dev.itayp.tasker.model.UserSettings
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UpdateUserSettingsRequest(
    @field:Size(max = 100) val displayName: String?,
    @field:Size(max = 4000) val contextBlock: String?,
    @field:NotBlank @field:Size(max = 64) val timeZone: String,
    @field:NotBlank @field:Size(max = 16) val preferredLanguage: String,
    val calendarInviteEmail: Boolean = false,
    val appReminders: Boolean = true,
    @field:Size(max = 32) val gender: String? = null,
    @field:Size(max = 2000) val agentDescription: String? = null,
    @field:Size(max = 100) val planningCron: String? = null,
    @field:Size(max = 16) val weekStartDay: String? = null,
    @field:Min(1) val autoArchiveDays: Int? = null,
    val aiEnabled: Boolean = true,
    val aiEnhancedReminders: Boolean = true,
    val dailyDigestEnabled: Boolean = true,
    val dailyDigestDueTasks: Boolean = true,
    @field:Size(max = 64) val dailyDigestCron: String = UserSettings.DEFAULT_DAILY_DIGEST_CRON,
)
