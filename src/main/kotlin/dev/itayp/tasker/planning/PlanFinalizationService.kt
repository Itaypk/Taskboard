package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.Locale

@Service
class PlanFinalizationService(
    private val planningSessionService: PlanningSessionService,
    private val backlogTaskService: BacklogTaskService,
    private val plannedTaskService: PlannedTaskService,
    private val userRepository: UserRepository,
    private val userSettingsService: UserSettingsService,
    private val planInviteDispatcher: PlanInviteDispatcher,
    private val emailProperties: EmailProperties,
    private val userCrypto: UserCryptoService,
) {
    private val log = LoggerFactory.getLogger(PlanFinalizationService::class.java)

    fun complete(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        log.debug("Completing agreed plan {}", plan)
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
        dispatchInvitesIfEligible(userId, AgreedPlan(tasks = listOf(task), summary = ""))
    }

    private fun applyPlan(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        val newTaskIds = plan.tasks.map { it.taskId }.toSet()
        // Snapshot before persist so we can detect which tasks were removed
        val removedTasks = plannedTaskService.findForSession(userId, sessionId)
            .filter { it.taskId !in newTaskIds }

        plannedTaskService.persist(sessionId, userId, plan.tasks)

        if (newTaskIds.isNotEmpty()) {
            backlogTaskService.stampPlanningSession(userId, newTaskIds.toList(), sessionId)
        }
        val removedTaskIds = removedTasks.map { it.taskId }
        if (removedTaskIds.isNotEmpty()) {
            backlogTaskService.clearPlanningSessionStamp(userId, removedTaskIds)
        }

        dispatchInvitesIfEligible(userId, plan)
        if (removedTasks.isNotEmpty()) {
            dispatchCancellationsIfEligible(userId, removedTasks)
        }

        log.debug(
            "applyPlan session={} added={} removed={}",
            sessionId,
            plan.tasks.map { t -> mapOf("id" to t.taskId, "slots" to t.slots.map { it.startIso to it.endIso }) },
            removedTasks.map { t -> mapOf("id" to t.taskId, "slots" to t.slots.map { it.startIso to it.endIso }) },
        )
    }

    private data class EmailContext(val email: String, val locale: Locale)

    private fun resolveEmailContext(userId: UUID): EmailContext? {
        val settings = userSettingsService.getOrCreate(userId)
        if (!settings.calendarInviteEmail) return null
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (user.emailVerifiedAt == null) return null
        val email = userCrypto.decrypt(userId, user.email)
        if (email.isNullOrBlank()) return null
        return EmailContext(email, userSettingsService.getLocale(userId))
    }

    private fun dispatchInvitesIfEligible(userId: UUID, plan: AgreedPlan) {
        val ctx = resolveEmailContext(userId) ?: return
        planInviteDispatcher.dispatch(
            userEmail = ctx.email,
            organizerEmail = emailProperties.from,
            organizerName = emailProperties.fromName,
            plan = plan,
            locale = ctx.locale,
        )
    }

    private fun dispatchCancellationsIfEligible(userId: UUID, removedTasks: List<AgreedPlanTask>) {
        val ctx = resolveEmailContext(userId) ?: return
        planInviteDispatcher.dispatchCancellations(
            userEmail = ctx.email,
            organizerEmail = emailProperties.from,
            organizerName = emailProperties.fromName,
            tasks = removedTasks,
            locale = ctx.locale,
        )
    }
}
