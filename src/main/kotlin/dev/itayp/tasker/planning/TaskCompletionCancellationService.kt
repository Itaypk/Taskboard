package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.notification.SlotReminderService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * When a task with an assigned time block is completed ahead of that block, its reminders and
 * calendar invitation are no longer needed. This mirrors what a plan revision already does for a
 * slot that's dropped from the plan ([PlanFinalizationService.applyPlan]), but is triggered by task
 * completion instead of a new agreed plan — so it re-uses the same cancellation primitives
 * ([SlotReminderService], [PlanInviteDispatcher]) rather than the slot-diffing machinery, since only
 * one task's still-planned slots are involved. Slots that already started are left alone; there's
 * nothing to cancel once a block is underway or past.
 */
@Service
class TaskCompletionCancellationService(
    private val plannedTaskService: PlannedTaskService,
    private val slotReminderService: SlotReminderService,
    private val planInviteDispatcher: PlanInviteDispatcher,
    private val inviteDeliveryResolver: InviteDeliveryResolver,
    private val emailProperties: EmailProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(TaskCompletionCancellationService::class.java)

    fun cancelUpcomingSlots(userId: UUID, sessionId: UUID, taskId: UUID) {
        val task = plannedTaskService.findTaskInSession(userId, sessionId, taskId) ?: return
        val now = OffsetDateTime.now(clock)
        val futureSlots = task.slots.filter { slot ->
            runCatching { OffsetDateTime.parse(slot.startIso) }.getOrNull()?.isAfter(now) == true
        }
        if (futureSlots.isEmpty()) return

        log.debug("Cancelling {} upcoming slot(s) for completed task in session {}", futureSlots.size, sessionId)
        slotReminderService.cancelForTask(sessionId, taskId, futureSlots)

        val ctx = inviteDeliveryResolver.resolveEmailContext(userId) ?: return
        planInviteDispatcher.dispatchCancellations(
            ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
            listOf(task.copy(slots = futureSlots)), ctx.locale,
        )
    }
}
