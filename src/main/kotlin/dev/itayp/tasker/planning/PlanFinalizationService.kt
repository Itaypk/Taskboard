package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.notification.SlotReminderService
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PlanFinalizationService(
    private val planningSessionService: PlanningSessionService,
    private val backlogTaskService: BacklogTaskService,
    private val plannedTaskService: PlannedTaskService,
    private val planInviteDispatcher: PlanInviteDispatcher,
    private val emailProperties: EmailProperties,
    private val inviteDeliveryResolver: InviteDeliveryResolver,
    private val planWatermarkService: PlanWatermarkService,
    private val slotReminderService: SlotReminderService,
) {
    private val log = LoggerFactory.getLogger(PlanFinalizationService::class.java)

    fun complete(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        log.debug("Completing agreed plan {}", plan)
        val session = planningSessionService.findById(userId, sessionId)
            ?: throw NoSuchElementException("Planning session $sessionId not found")
        // Bump carry-over reschedule counts BEFORE this plan is written, resolving "previous" by the
        // week immediately before the one being finalized (not the globally-latest completed plan, which
        // may be a week planned ahead). Doing it here (not at session start) means an abandoned session
        // never touches the existing plan's task stats.
        planningSessionService.bumpRescheduleCountsForCarriedOverTasks(userId, session.weekStart)
        planningSessionService.completeSession(userId, sessionId, plan.summary)
        applyPlan(userId, sessionId, plan)
    }

    fun revisePlan(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        log.debug("Revising agreed plan for session {}", sessionId)
        planningSessionService.updateSummary(sessionId, plan.summary)
        applyPlan(userId, sessionId, plan)
    }

    fun addTaskToSession(userId: UUID, sessionId: UUID, task: AgreedPlanTask) {
        plannedTaskService.upsertSingleTask(sessionId, userId, task)
        backlogTaskService.stampPlanningSession(userId, listOf(task.taskId), sessionId)
        planWatermarkService.bump(userId)
        dispatchInvitesIfEligible(userId, AgreedPlan(tasks = listOf(task), summary = ""))
        slotReminderService.sync(userId, sessionId, previous = emptyList(), current = listOf(task))
    }

    private fun applyPlan(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        val newTaskIds = plan.tasks.map { it.taskId }.toSet()
        // Snapshot before persist so we can diff against the previous plan.
        val previousTasks = plannedTaskService.findForSession(userId, sessionId)

        plannedTaskService.persist(sessionId, userId, plan.tasks)

        if (newTaskIds.isNotEmpty()) {
            backlogTaskService.stampPlanningSession(userId, newTaskIds.toList(), sessionId)
        }
        val removedTaskIds = previousTasks.map { it.taskId }.filter { it !in newTaskIds }
        if (removedTaskIds.isNotEmpty()) {
            backlogTaskService.clearPlanningSessionStamp(userId, removedTaskIds)
        }

        planWatermarkService.bump(userId)
        dispatchInviteDiffIfEligible(userId, previousTasks, plan.tasks)
        slotReminderService.sync(userId, sessionId, previousTasks, plan.tasks)
    }

    /**
     * Identifies a scheduled slot by `(taskId, startIso)` — the same key the calendar UID is built
     * from. A time move therefore reads as a removal of the old slot plus an addition of the new one
     * (the user gets a cancellation + a fresh invite), while a same-time edit to the end time, label,
     * title, or notes reads as an update to the existing event.
     */
    private data class SlotRef(val task: AgreedPlanTask, val slot: AgreedTimeSlot) {
        val key: String get() = "${task.taskId}|${slot.startIso}"
    }

    private data class InviteDiff(
        val added: List<AgreedPlanTask>,
        val changed: List<AgreedPlanTask>,
        val removed: List<AgreedPlanTask>,
    )

    private fun computeInviteDiff(previous: List<AgreedPlanTask>, current: List<AgreedPlanTask>): InviteDiff {
        fun index(tasks: List<AgreedPlanTask>): Map<String, SlotRef> =
            tasks.flatMap { t -> t.slots.map { s -> SlotRef(t, s) } }.associateBy { it.key }

        val prev = index(previous)
        val curr = index(current)

        val added = curr.filterKeys { it !in prev }.values
        val removed = prev.filterKeys { it !in curr }.values
        val changed = curr.filterValues { new ->
            val old = prev[new.key] ?: return@filterValues false
            old.slot.endIso != new.slot.endIso ||
                old.slot.label != new.slot.label ||
                old.task.title != new.task.title ||
                old.task.notes != new.task.notes
        }.values

        return InviteDiff(regroup(added), regroup(changed), regroup(removed))
    }

    /** Reassembles per-slot refs back into [AgreedPlanTask]s carrying only the slots in this bucket. */
    private fun regroup(refs: Collection<SlotRef>): List<AgreedPlanTask> =
        refs.groupBy { it.task.taskId }.map { (_, group) ->
            group.first().task.copy(slots = group.map { it.slot })
        }

    private fun dispatchInviteDiffIfEligible(
        userId: UUID,
        previous: List<AgreedPlanTask>,
        current: List<AgreedPlanTask>,
    ) {
        val diff = computeInviteDiff(previous, current)
        log.debug(
            "Invite diff for session user {}: addedSlots={} changedSlots={} removedSlots={}",
            userId,
            diff.added.sumOf { it.slots.size },
            diff.changed.sumOf { it.slots.size },
            diff.removed.sumOf { it.slots.size },
        )
        if (diff.added.isEmpty() && diff.changed.isEmpty() && diff.removed.isEmpty()) return

        val ctx = inviteDeliveryResolver.resolveEmailContext(userId) ?: return
        if (diff.added.isNotEmpty()) {
            planInviteDispatcher.dispatch(
                ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                AgreedPlan(diff.added, summary = ""), ctx.locale,
            )
        }
        if (diff.changed.isNotEmpty()) {
            planInviteDispatcher.dispatchUpdates(
                ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                AgreedPlan(diff.changed, summary = ""), ctx.locale,
            )
        }
        if (diff.removed.isNotEmpty()) {
            planInviteDispatcher.dispatchCancellations(
                ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                diff.removed, ctx.locale,
            )
        }
    }

    private fun dispatchInvitesIfEligible(userId: UUID, plan: AgreedPlan) {
        val ctx = inviteDeliveryResolver.resolveEmailContext(userId) ?: return
        log.debug("Dispatching {} calendar invite(s) for user {}", plan.tasks.sumOf { it.slots.size }, userId)
        planInviteDispatcher.dispatch(
            userEmail = ctx.email,
            organizerEmail = emailProperties.scheduling.from,
            organizerName = emailProperties.scheduling.fromName,
            plan = plan,
            locale = ctx.locale,
        )
    }
}
