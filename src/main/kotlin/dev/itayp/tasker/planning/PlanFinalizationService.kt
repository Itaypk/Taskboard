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
        if (task.taskId != null) {
            backlogTaskService.stampPlanningSession(userId, listOf(task.taskId), sessionId)
        }
        dispatchInvitesIfEligible(userId, AgreedPlan(tasks = listOf(task), summary = ""))
    }

    private fun applyPlan(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        plannedTaskService.persist(sessionId, userId, plan.tasks)
        val taskIds = plan.tasks.mapNotNull { it.taskId }
        if (taskIds.isNotEmpty()) {
            backlogTaskService.stampPlanningSession(userId, taskIds, sessionId)
        }
        dispatchInvitesIfEligible(userId, plan)
    }

    private fun dispatchInvitesIfEligible(userId: UUID, plan: AgreedPlan) {
        val settings = userSettingsService.getOrCreate(userId)
        if (!settings.calendarInviteEmail) return

        val user = userRepository.findById(userId).orElse(null) ?: return
        if (user.emailVerifiedAt == null) return
        val email = userCrypto.decrypt(userId, user.email)
        if (email.isNullOrBlank()) return

        val locale = userSettingsService.getLocale(userId)

        planInviteDispatcher.dispatch(
            userEmail = email,
            organizerEmail = emailProperties.from,
            organizerName = emailProperties.fromName,
            plan = plan,
            locale = locale,
        )
    }
}
