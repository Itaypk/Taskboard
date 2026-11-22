package dev.itayp.tasker.notification

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Decides whether a snoozed slot reminder still refers to the task's current plan (issue #401).
 *
 * A snooze copies the original reminder's session and slot, and fires regardless of slot timing, so
 * the write-time cancellations ([SlotReminderService.sync], the task-completion cancellation) can't
 * reach it: those only cancel reminders for slots removed within the same session, or for slots still
 * in the future. Checking at delivery (and at snooze-tap) time instead covers every way the reminder
 * can go stale, including a snooze tapped on an old message after the plan changed.
 *
 * Rescheduling is judged by the user's *own* planned tasks rather than
 * [BacklogTask.lastScheduledInSessionId], which is per task: on a shared board a co-member planning
 * the task into their week would otherwise drop this user's snooze.
 */
@Component
class SnoozeRelevanceCheck(
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
) {

    /** Returns why [notification] no longer applies to [task] (for a debug log), or null if it still does. */
    @Transactional(readOnly = true)
    fun staleReason(notification: ScheduledNotificationEntity, task: BacklogTask): String? {
        if (task.status != TaskStatus.TODO) return "task is ${task.status}"

        val placements = plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(notification.userId!!, listOf(task.id))
        val slotsByPlacement = plannedTaskSlotRepository
            .findAllByPlannedTaskIdIn(placements.mapNotNull { it.id })
            .groupBy { it.plannedTaskId }

        val own = placements.firstOrNull { it.sessionId == notification.sessionId }
            ?: return "task removed from its plan"
        if (slotsByPlacement[own.id].orEmpty().none { it.startIso == notification.slotStartIso }) {
            return "slot removed from its plan"
        }

        val snoozedStart = parseInstant(notification.slotStartIso) ?: return null
        val rescheduled = placements
            .filter { it.sessionId != notification.sessionId }
            .flatMap { slotsByPlacement[it.id].orEmpty() }
            .any { slot -> parseInstant(slot.startIso)?.isAfter(snoozedStart) == true }
        return if (rescheduled) "task rescheduled in a later plan" else null
    }

    private fun parseInstant(iso: String?): Instant? =
        iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
}
