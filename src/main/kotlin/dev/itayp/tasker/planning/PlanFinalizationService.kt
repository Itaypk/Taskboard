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

    /**
     * Reschedules a single already-planned task to [newSlot], leaving the rest of the plan untouched.
     * The quick-switch counterpart to editing a slot through a full plan revision: it snapshots the
     * task's current slot(s) and drives the change through the same `(taskId, startIso)` slot diff
     * ([dispatchInviteDiffIfEligible] + [SlotReminderService.sync]), so a time move cancels the old
     * calendar invite + reminder (including a snoozed one) and issues the new one, while a same-start
     * edit updates in place. Returns false if the task isn't in the session's plan.
     */
    fun changeTaskSlot(userId: UUID, sessionId: UUID, taskId: UUID, newSlot: AgreedTimeSlot): Boolean {
        val previous = plannedTaskService.findTaskInSession(userId, sessionId, taskId) ?: return false
        val updated = previous.copy(slots = listOf(newSlot))
        plannedTaskService.upsertSingleTask(sessionId, userId, updated)
        planWatermarkService.bump(userId)
        dispatchInviteDiffIfEligible(userId, listOf(previous), listOf(updated))
        slotReminderService.sync(userId, sessionId, listOf(previous), listOf(updated))
        return true
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

    private fun dispatchInviteDiffIfEligible(
        userId: UUID,
        previous: List<AgreedPlanTask>,
        current: List<AgreedPlanTask>,
    ) {
        val diff = PlanSlotDiffer.diff(previous, current)
        log.debug(
            "Invite diff for session user {}: addedSlots={} changedSlots={} removedSlots={}",
            userId,
            diff.added.size,
            diff.changed.size,
            diff.removed.size,
        )
        if (diff.added.isEmpty() && diff.changed.isEmpty() && diff.removed.isEmpty()) return

        val ctx = inviteDeliveryResolver.resolveEmailContext(userId) ?: return
        if (diff.added.isNotEmpty()) {
            planInviteDispatcher.dispatch(
                ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                AgreedPlan(PlanSlotDiffer.regroup(diff.added), summary = ""), ctx.locale,
            )
        }
        if (diff.changed.isNotEmpty()) {
            planInviteDispatcher.dispatchUpdates(
                ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                AgreedPlan(PlanSlotDiffer.regroup(diff.changed), summary = ""), ctx.locale,
            )
        }
        if (diff.removed.isNotEmpty()) {
            planInviteDispatcher.dispatchCancellations(
                ctx.email, emailProperties.scheduling.from, emailProperties.scheduling.fromName,
                PlanSlotDiffer.regroup(diff.removed), ctx.locale,
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
