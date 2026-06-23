package dev.itayp.tasker.notification

import java.util.UUID

/**
 * Emitted by [NotificationScheduler] when a slot reminder comes due. Carries identifiers only; the
 * handler ([SlotReminderDispatcher]) resolves and decrypts the task title at delivery time and pushes
 * it to the user's channel.
 */
data class SlotReminderDueEvent(
    val notificationId: UUID,
    val userId: UUID,
    val sessionId: UUID,
    val backlogTaskId: UUID,
    val slotStartIso: String,
    val slotEndIso: String,
)
