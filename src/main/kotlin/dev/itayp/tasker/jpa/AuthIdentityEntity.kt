package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A single external login identity belonging to a user. One user may have several
 * (e.g. Telegram + email + Google); each row maps one `(provider, providerUserId)`
 * pair to a [UserEntity]. This is the lookup key for authentication — profile/display
 * data (Telegram username, encrypted email, …) stays on [UserEntity].
 *
 * `providerUserId` is always a non-secret, stable handle: the numeric Telegram id as
 * text, the email hash (never the plaintext address), an OAuth `sub`, etc.
 */
@Entity
@Table(name = "auth_identities")
class AuthIdentityEntity {
    @Id
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    /** One of [AuthProvider]. Stored as a plain string so new providers need no schema change. */
    @Column(nullable = false, length = 32)
    var provider: String? = null

    @Column(name = "provider_user_id", nullable = false)
    var providerUserId: String? = null

    /** When ownership of this identity was proven (HMAC for Telegram, magic-link click for email). */
    @Column(name = "verified_at")
    var verifiedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
}

/** Known auth providers. Values match the strings stored in [AuthIdentityEntity.provider]. */
object AuthProvider {
    const val TELEGRAM = "telegram"
    const val EMAIL = "email"
    const val GOOGLE = "google"
}
