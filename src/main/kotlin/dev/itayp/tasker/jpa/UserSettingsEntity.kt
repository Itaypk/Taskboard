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

    @Column(name = "context_block")
    var contextBlock: String? = null

    @Column(name = "time_zone", nullable = false)
    var timeZone: String = "UTC"

    @Column(name = "preferred_language", nullable = false)
    var preferredLanguage: String = "en"

    @Column(name = "calendar_invite_email", nullable = false)
    var calendarInviteEmail: Boolean = false

    @Column(name = "gender")
    var gender: String? = null

    @Column(name = "agent_description", columnDefinition = "TEXT")
    var agentDescription: String? = null

    @Column(name = "planning_cron")
    var planningCron: String? = null

    @Column(name = "week_start_day")
    var weekStartDay: String? = null
}
