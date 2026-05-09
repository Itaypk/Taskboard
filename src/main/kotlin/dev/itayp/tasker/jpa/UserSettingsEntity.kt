package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "user_settings")
open class UserSettingsEntity {
    @Id
    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "display_name")
    var displayName: String? = null

    @Column(name = "context_block", columnDefinition = "TEXT")
    var contextBlock: String? = null

    @Column(name = "time_zone", nullable = false)
    var timeZone: String = "UTC"

    @Column(name = "preferred_language", nullable = false)
    var preferredLanguage: String = "en"

    @Column(name = "calendar_invite_email", nullable = false)
    var calendarInviteEmail: Boolean = false

    @Column(name = "gender")
    var gender: String? = null

    @Column(name = "assistant_name", length = 100)
    var assistantName: String? = null

    @Column(name = "assistant_gender", length = 20)
    var assistantGender: String? = null
}
