package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import dev.itayp.tasker.model.UserSettings
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_settings")
class UserSettingsEntity {
    @Id
    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "display_name")
    var displayName: ByteArray? = null

    @Column(name = "context_block")
    var contextBlock: ByteArray? = null

    @Column(name = "time_zone", nullable = false)
    var timeZone: String = "UTC"

    /** True from registration until the browser's zone has been reported (see UserSettingsService.applyDetectedTimeZone). */
    @Column(name = "time_zone_detection_pending", nullable = false)
    var timeZoneDetectionPending: Boolean = false

    @Column(name = "preferred_language", nullable = false)
    var preferredLanguage: String = "en-US"

    @Column(name = "calendar_invite_email", nullable = false)
    var calendarInviteEmail: Boolean = false

    @Column(name = "app_reminders", nullable = false)
    var appReminders: Boolean = true

    @Column(name = "gender")
    var gender: String? = null

    @Column(name = "agent_description")
    var agentDescription: ByteArray? = null

    @Column(name = "planning_cron")
    var planningCron: String? = null

    @Column(name = "week_start_day")
    var weekStartDay: String? = null

    @Column(name = "auto_archive_days")
    var autoArchiveDays: Int? = null

    // Both default off: a fresh row is ungranted (ai_tier = "none") until
    // UserSettingsService.initializeForNewUser decides otherwise, and ai_enabled has nothing to
    // gate yet. See dev.itayp.tasker.ai.access.AiTier.
    @Column(name = "ai_enabled", nullable = false)
    var aiEnabled: Boolean = false

    @Column(name = "ai_enhanced_reminders", nullable = false)
    var aiEnhancedReminders: Boolean = true

    @Column(name = "ai_tier", nullable = false)
    var aiTier: String = "none"

    @Column(name = "daily_digest_enabled", nullable = false)
    var dailyDigestEnabled: Boolean = true

    @Column(name = "daily_digest_due_tasks", nullable = false)
    var dailyDigestDueTasks: Boolean = true

    /** Spring 6-field cron in the user's zone, restricted to at most once a day (see docs/DAILY-DIGEST.md). */
    @Column(name = "daily_digest_cron", nullable = false)
    var dailyDigestCron: String = UserSettings.DEFAULT_DAILY_DIGEST_CRON

    /** The digest scheduler's watermark: when the digest was last evaluated (sent, empty or skipped). */
    @Column(name = "daily_digest_last_run_at")
    var dailyDigestLastRunAt: Instant? = null
}
