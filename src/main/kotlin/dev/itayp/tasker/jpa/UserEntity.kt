package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
open class UserEntity {
    @Id
    var id: UUID? = null

    @Column(name = "telegram_id", unique = true)
    var telegramId: Long? = null

    @Column(name = "telegram_username")
    var telegramUsername: String? = null

    @Column(name = "telegram_first_name")
    var telegramFirstName: String? = null

    @Column(name = "telegram_photo_url", length = 1000)
    var telegramPhotoUrl: String? = null

    @Column
    var email: String? = null

    @Column(name = "is_demo", nullable = false)
    var isDemo: Boolean = false

    @Column(name = "demo_expires_at")
    var demoExpiresAt: Instant? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
}
