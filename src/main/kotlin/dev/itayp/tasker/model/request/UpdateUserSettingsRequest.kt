package dev.itayp.tasker.model.request

data class UpdateUserSettingsRequest(
    val displayName: String?,
    val contextBlock: String?,
    val timeZone: String,
    val preferredLanguage: String,
    val calendarInviteEmail: Boolean = false,
    val gender: String? = null,
    val agentDescription: String? = null,
    val planningCron: String? = null,
    val weekStartDay: String? = null,
)
