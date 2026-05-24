package dev.itayp.tasker.planning

import dev.itayp.tasker.repository.BacklogTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
class PlanningSessionService(
    private val planningSessionRepository: PlanningSessionRepository,
    private val backlogTaskChangeService: BacklogTaskChangeService,
    private val backlogTaskRepository: BacklogTaskRepository,
    private val clock: Clock,
) {

    @Transactional
    fun startSession(
        userId: UUID,
        weekStart: LocalDate,
        conversationId: UUID? = null,
    ): PlanningSessionEntity {
        val active = planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.ACTIVE)
        if (active != null && active.weekStart == weekStart) return active

        // Bump reschedule counts for tasks that were scheduled in the previous completed
        // session but never marked DONE — they're being carried into this new session.
        val previous = planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.COMPLETED)
        if (previous?.id != null) {
            backlogTaskRepository.incrementRescheduleCountForUnfinishedTasks(userId, previous.id!!)
        }

        return planningSessionRepository.save(PlanningSessionEntity().apply {
            this.userId = userId
            this.conversationId = conversationId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = clock.instant()
            this.weekStart = weekStart
        })
    }

    @Transactional(readOnly = true)
    fun findSessionForWeek(userId: UUID, weekStart: LocalDate): PlanningSessionEntity? =
        planningSessionRepository.findFirstByUserIdAndWeekStartOrderByStartedAtDesc(userId, weekStart)

    @Transactional
    fun completeSession(userId: UUID, sessionId: UUID, summary: String?): PlanningSessionEntity =
        endSession(userId, sessionId, PlanningSessionStatus.COMPLETED, summary)

    @Transactional
    fun abandonSession(userId: UUID, sessionId: UUID): PlanningSessionEntity =
        endSession(userId, sessionId, PlanningSessionStatus.ABANDONED, summary = null)

    private fun endSession(
        userId: UUID,
        sessionId: UUID,
        status: PlanningSessionStatus,
        summary: String?,
    ): PlanningSessionEntity {
        val session = planningSessionRepository.findByIdAndUserId(sessionId, userId)
            ?: throw NoSuchElementException("Planning session $sessionId not found")
        session.status = status
        session.endedAt = clock.instant()
        if (summary != null) session.summary = summary
        return planningSessionRepository.save(session)
    }

    @Transactional
    fun updateSummary(sessionId: UUID, summary: String?) {
        val session = planningSessionRepository.findById(sessionId).orElseThrow()
        session.summary = summary
        planningSessionRepository.save(session)
    }

    @Transactional(readOnly = true)
    fun findById(userId: UUID, sessionId: UUID): PlanningSessionEntity? =
        planningSessionRepository.findByIdAndUserId(sessionId, userId)

    @Transactional(readOnly = true)
    fun findActiveSession(userId: UUID): PlanningSessionEntity? =
        planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.ACTIVE)

    /**
     * The session that should be treated as "the user's current weekly plan" in the UI.
     * Prefers an in-progress session; falls back to the most recently completed one so the
     * last finalized plan stays visible until a new one is started. ABANDONED sessions
     * never count.
     */
    @Transactional(readOnly = true)
    fun findCurrentPlan(userId: UUID): PlanningSessionEntity? =
        findActiveSession(userId)
            ?: planningSessionRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.COMPLETED)

    /**
     * Returns the session whose summary should be prepended to the next session's prompt.
     * Skips ABANDONED sessions, falling through to the most recent COMPLETED one.
     */
    @Transactional(readOnly = true)
    fun findPreviousSummarizableSession(userId: UUID): PlanningSessionEntity? =
        planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.COMPLETED)

    /**
     * Computes the diff of backlog task changes between the user's previous completed planning
     * session and now. If no prior session exists the result is empty.
     */
    @Transactional(readOnly = true)
    fun diffSincePreviousSession(userId: UUID): TaskChangeSummary {
        val previous = findPreviousSummarizableSession(userId)
            ?: return TaskChangeSummary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0)
        val since = previous.endedAt ?: previous.startedAt
            ?: return TaskChangeSummary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0)
        return backlogTaskChangeService.summarizeSince(userId, since)
    }
}
