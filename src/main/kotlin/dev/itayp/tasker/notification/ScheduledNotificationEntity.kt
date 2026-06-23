package dev.itayp.tasker.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

/**
 * A single app-driven notification queued for delivery. Phase 1 only materializes and fires these
 * (publishing [SlotReminderDueEvent]); the actual channel delivery is a later phase.
 *
 * Deliberately holds identifiers and timestamps only — no task titles/notes — so nothing here needs
 * DEK encryption. The handler resolves and decrypts user-authored content at send time.
 */
@Entity
@Table(name = "scheduled_notification")
class ScheduledNotificationEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "session_id", nullable = false)
    var sessionId: UUID? = null

    @Column(name = "backlog_task_id", nullable = false)
    var backlogTaskId: UUID? = null

    @Column(name = "slot_start_iso", nullable = false, length = 64)
    var slotStartIso: String? = null

    @Column(name = "slot_end_iso", nullable = false, length = 64)
    var slotEndIso: String? = null

    @Column(nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    var type: NotificationType = NotificationType.SLOT_REMINDER

    @Column(name = "fire_at", nullable = false)
    var fireAt: Instant? = null

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: NotificationStatus = NotificationStatus.PENDING

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null
}
