package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity {
    @Id
    var id: UUID? = null

    @Column(name = "telegram_id", unique = true)
    var telegramId: Long? = null

    @Column(name = "telegram_username")
    var telegramUsername: String? = null

    @Column(name = "telegram_first_name")
    var telegramFirstName: ByteArray? = null

    @Column(name = "telegram_photo_url", length = 1000)
    var telegramPhotoUrl: String? = null

    /**
     * When the bot last learned it can write to [telegramId]'s private chat; null while it can't (or
     * doesn't know). A Telegram identity alone isn't enough — a bot can't message someone who never
     * wrote to it. Maintained by `TelegramReachabilityService`.
     */
    @Column(name = "telegram_chat_ready_at")
    var telegramChatReadyAt: Instant? = null

    @Column
    var email: ByteArray? = null

    @Column(name = "email_hash", length = 64)
    var emailHash: String? = null

    @Column(name = "email_verified_at")
    var emailVerifiedAt: Instant? = null

    @Column(name = "email_verification_token", length = 64)
    var emailVerificationToken: String? = null

    @Column(name = "email_verification_token_expires_at")
    var emailVerificationTokenExpiresAt: Instant? = null

    @Column(name = "is_demo", nullable = false)
    var isDemo: Boolean = false

    @Column(name = "demo_expires_at")
    var demoExpiresAt: Instant? = null

    /** One-way latch: true once a login identity or verified email is attached. The hard guard that keeps cleanup off real accounts. */
    @Column(name = "claimed", nullable = false)
    var claimed: Boolean = false

    /** One-way: stamped on the first genuine (non-tutorial) write. Promotes an unclaimed account to the longer inactivity TTL. */
    @Column(name = "engaged_at")
    var engagedAt: Instant? = null

    /** Rolling activity marker, refreshed (throttled) on authenticated API requests; drives inactivity-based cleanup. */
    @Column(name = "last_active_at")
    var lastActiveAt: Instant? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
}
