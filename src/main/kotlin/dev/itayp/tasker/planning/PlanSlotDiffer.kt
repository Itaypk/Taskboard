package dev.itayp.tasker.planning

import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot

/**
 * Diffs two finalized-plan snapshots by `(taskId, startIso)` — the same identity the calendar invite
 * UID is built from. A time move therefore reads as a removal of the old slot plus an addition of the
 * new one, while a same-start edit (end time, title, notes) reads as a *change* to the existing
 * slot. `label` is deliberately excluded from the change check: neither the calendar invite
 * ([PlanInviteDispatcher]) nor the slot reminder text renders it, so diffing it only produced
 * false-positive "changed" invites that reset the recipient's RSVP for no visible reason.
 *
 * Both the calendar-invite dispatch ([PlanFinalizationService]) and the in-app slot reminders
 * ([dev.itayp.tasker.notification.SlotReminderService]) diff plans this way; keeping the keying in one
 * place is what keeps the two reminder mechanisms consistent.
 */
object PlanSlotDiffer {

    data class SlotRef(val task: AgreedPlanTask, val slot: AgreedTimeSlot) {
        val key: String get() = "${task.taskId}|${slot.startIso}"
    }

    data class Diff(
        val added: List<SlotRef>,
        val changed: List<SlotRef>,
        val removed: List<SlotRef>,
    )

    fun diff(previous: List<AgreedPlanTask>, current: List<AgreedPlanTask>): Diff {
        val prev = index(previous)
        val curr = index(current)

        val added = curr.filterKeys { it !in prev }.values.toList()
        val removed = prev.filterKeys { it !in curr }.values.toList()
        val changed = curr.filterValues { new ->
            val old = prev[new.key] ?: return@filterValues false
            old.slot.endIso != new.slot.endIso ||
                old.task.title != new.task.title ||
                old.task.notes != new.task.notes
        }.values.toList()

        return Diff(added, changed, removed)
    }

    /** Reassembles per-slot refs back into [AgreedPlanTask]s carrying only the slots in this bucket. */
    fun regroup(refs: Collection<SlotRef>): List<AgreedPlanTask> =
        refs.groupBy { it.task.taskId }.map { (_, group) ->
            group.first().task.copy(slots = group.map { it.slot })
        }

    private fun index(tasks: List<AgreedPlanTask>): Map<String, SlotRef> =
        tasks.flatMap { t -> t.slots.map { s -> SlotRef(t, s) } }.associateBy { it.key }
}
