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
    val gender: String?,
    val agentDescription: String?,
    val planningCron: String?,
    val weekStartDay: String?,
    val autoArchiveDays: Int?,
    val aiEnabled: Boolean = true,
    val aiTier: String = "standard",
)
