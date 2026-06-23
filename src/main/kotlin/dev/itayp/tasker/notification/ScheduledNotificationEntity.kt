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
 * A single app-driven notification queued for delivery. [SlotReminderService] materializes these,
 * [NotificationScheduler] fires due ones (publishing [SlotReminderDueEvent]), and
 * [SlotReminderDispatcher] delivers them and records the terminal [status].
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

    /** Delivery attempts so far; a transient send failure leaves the row PENDING for retry up to a cap. */
    @Column(nullable = false)
    var attempts: Int = 0

    /**
     * True for rows re-queued by a user's "snooze" tap. The dispatcher skips its
     * "slot start already passed -> EXPIRED" gate for these, so a snooze always fires at [fireAt]
     * regardless of the (now possibly past) slot timing — snoozing applies to the notification, not
     * the calendar slot.
     */
    @Column(nullable = false)
    var snoozed: Boolean = false
}
