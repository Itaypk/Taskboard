package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.planning.dto.AgreedPlan
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
) {
    private val log = LoggerFactory.getLogger(PlanFinalizationService::class.java)

    fun complete(userId: UUID, sessionId: UUID, plan: AgreedPlan) {
        log.debug("Completing agreed plan {}", plan)
        planningSessionService.completeSession(userId, sessionId, plan.summary)
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
        if (user.emailVerifiedAt == null || user.email.isNullOrBlank()) return

        planInviteDispatcher.dispatch(
            userEmail = user.email!!,
            organizerEmail = emailProperties.from,
            organizerName = emailProperties.fromName,
            plan = plan,
        )
    }
}
