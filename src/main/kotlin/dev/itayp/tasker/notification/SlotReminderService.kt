package dev.itayp.tasker.notification

import dev.itayp.tasker.planning.PlanSlotDiffer
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Materializes slot reminders into [ScheduledNotificationEntity] rows when a plan is finalized or
 * revised, keeping them in sync with the plan's slots. A reminder is keyed by `(taskId, startIso)` —
 * the same identity the calendar invite UID uses — so a time move reads as a removal of the old slot
 * plus an addition of the new one, exactly like the invite diff in `PlanFinalizationService`.
 *
 * Unlike calendar invites, reminders are created regardless of email eligibility: app-driven
 * notifications are precisely how we reach channel-less / email-less users.
 */
@Service
class SlotReminderService(
    private val repository: ScheduledNotificationRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SlotReminderService::class.java)

    @Transactional
    fun sync(userId: UUID, sessionId: UUID, previous: List<AgreedPlanTask>, current: List<AgreedPlanTask>) {
        // Same `(taskId, startIso)` keying as the calendar invites; a same-start edit (a "changed"
        // slot) leaves the fire time untouched, so only added/removed slots affect reminders.
        val diff = PlanSlotDiffer.diff(previous, current)
        val now = clock.instant()

        // Removed slots: cancel any reminder still pending for them.
        var cancelled = 0
        for (ref in diff.removed) {
            cancelled += repository.cancelPending(sessionId, ref.task.taskId, ref.slot.startIso)
        }

        // Added slots: queue a reminder if it would fire in the future.
        var created = 0
        for (ref in diff.added) {
            val fireAt = runCatching { OffsetDateTime.parse(ref.slot.startIso).toInstant().minus(LEAD_TIME) }
                .getOrElse {
                    log.warn("Skipping reminder for session {}: unparseable start '{}'", sessionId, ref.slot.startIso)
                    null
                } ?: continue
            if (!fireAt.isAfter(now)) continue

            repository.save(
                ScheduledNotificationEntity().apply {
                    this.userId = userId
                    this.sessionId = sessionId
                    this.backlogTaskId = ref.task.taskId
                    this.slotStartIso = ref.slot.startIso
                    this.slotEndIso = ref.slot.endIso
                    this.type = NotificationType.SLOT_REMINDER
                    this.fireAt = fireAt
                    this.status = NotificationStatus.PENDING
                    this.createdAt = now
                },
            )
            created++
        }

        if (created > 0 || cancelled > 0) {
            log.debug("Slot reminders synced for user {}: created={} cancelled={}", userId, created, cancelled)
        }
    }

    companion object {
        /** Matches the iCal VALARM (TRIGGER:-PT15M) so in-app and calendar reminders feel consistent. */
        private val LEAD_TIME: Duration = Duration.ofMinutes(15)
    }
}
