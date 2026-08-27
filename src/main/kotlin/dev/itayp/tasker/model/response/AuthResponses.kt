package dev.itayp.tasker.model.response

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserEntity

data class MeResponse(
    val id: String,
    val telegramId: Long?,
    val telegramUsername: String?,
    val telegramFirstName: String?,
    val telegramPhotoUrl: String?,
    val email: String?,
    /** False while the account has no login identity yet — the SPA shows a "save your account" nudge. */
    val claimed: Boolean,
    /** Stored UI-language preference tag (e.g. `en-US`, `he`). Lets the SPA set its i18n locale on
     * boot without a second settings fetch (docs/I18N.md, D3). */
    val preferredLanguage: String,
)

/** One linked login method for the "connected accounts" settings screen. Provider-agnostic. */
data class LinkedIdentityResponse(
    val provider: String,
    val linkedAt: String?,
    val lastLoginAt: String?,
)

/**
 * One active session for the "active sessions" settings screen. Carries no session identifier:
 * revocation is all-or-others, so the UI never needs one, and handing session ids to a browser
 * that might itself be the attacker's buys nothing.
 */
data class ActiveSessionResponse(
    /** True for the session making this request — the UI marks it and it survives revoke-others. */
    val current: Boolean,
    /** Coarse label such as "Chrome on macOS"; null for sessions created before this was recorded. */
    val device: String?,
    val ipAddress: String?,
    val signedInAt: String,
    val lastActiveAt: String,
)

/** Result of "sign out everywhere else". */
data class RevokeSessionsResponse(val revoked: Int)

fun UserEntity.toMeResponse(crypto: UserCryptoService, preferredLanguage: String): MeResponse {
    val ownerId = id ?: error("UserEntity must have an id")
    return MeResponse(
        id = ownerId.toString(),
        telegramId = telegramId,
        telegramUsername = telegramUsername,
        telegramFirstName = crypto.decrypt(ownerId, telegramFirstName),
        telegramPhotoUrl = telegramPhotoUrl,
        email = crypto.decrypt(ownerId, email),
        claimed = claimed,
        preferredLanguage = preferredLanguage,
    )
}
