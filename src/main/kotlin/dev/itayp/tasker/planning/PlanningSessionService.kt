package dev.itayp.tasker.planning

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class PlanningSessionService(
    private val planningSessionRepository: PlanningSessionRepository,
    private val backlogTaskChangeService: BacklogTaskChangeService,
    private val clock: Clock,
) {

    @Transactional
    fun startSession(userId: UUID, conversationId: UUID? = null): PlanningSessionEntity {
        val active = planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.ACTIVE)
        if (active != null) return active

        return planningSessionRepository.save(PlanningSessionEntity().apply {
            this.userId = userId
            this.conversationId = conversationId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = clock.instant()
        })
    }

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

    @Transactional(readOnly = true)
    fun findById(userId: UUID, sessionId: UUID): PlanningSessionEntity? =
        planningSessionRepository.findByIdAndUserId(sessionId, userId)

    @Transactional(readOnly = true)
    fun findActiveSession(userId: UUID): PlanningSessionEntity? =
        planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.ACTIVE)

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
