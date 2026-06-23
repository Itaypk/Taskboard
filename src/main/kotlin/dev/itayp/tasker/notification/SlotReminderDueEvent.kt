package dev.itayp.tasker.notification

import java.util.UUID

/**
 * Emitted by [NotificationScheduler] when a slot reminder comes due. Carries identifiers only; the
 * handler resolves and decrypts the task title at delivery time. Phase 1 has a no-op listener
 * ([SlotReminderLogListener]); a later phase delivers it to the user's channel.
 */
data class SlotReminderDueEvent(
    val notificationId: UUID,
    val userId: UUID,
    val sessionId: UUID,
    val backlogTaskId: UUID,
    val slotStartIso: String,
    val slotEndIso: String,
)
