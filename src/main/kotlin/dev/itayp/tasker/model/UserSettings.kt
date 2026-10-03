package dev.itayp.tasker.model

import java.util.UUID

/**
 * Plaintext, decrypted view of a user's settings. Services produce this from
 * UserSettingsEntity via UserCryptoService so callers never see ciphertext.
 */
data class UserSettings(
    val userId: UUID,
    val displayName: String?,
    val contextBlock: String?,
    val timeZone: String,
    val preferredLanguage: String,
    val calendarInviteEmail: Boolean,
    val appReminders: Boolean = true,
    val gender: String?,
    val agentDescription: String?,
    val planningCron: String?,
    val weekStartDay: String?,
    val autoArchiveDays: Int?,
    val aiEnabled: Boolean = false,
    val aiEnhancedReminders: Boolean = true,
    val aiTier: String = "none",
    val dailyDigestEnabled: Boolean = true,
    val dailyDigestDueTasks: Boolean = true,
    val dailyDigestCron: String = DEFAULT_DAILY_DIGEST_CRON,
) {
    companion object {
        /** 08:00 every day, in the user's time zone. */
        const val DEFAULT_DAILY_DIGEST_CRON = "0 0 8 * * *"
    }
}
