package dev.itayp.tasker.model.request

data class UpdateUserSettingsRequest(
    val displayName: String?,
    val contextBlock: String?,
    val timeZone: String,
    val preferredLanguage: String,
    val calendarInviteEmail: Boolean = false,
    val gender: String? = null,
    val assistantName: String? = null,
    val assistantGender: String? = null,
)
