package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class PlanningSessionService(
    private val planningSessionRepository: PlanningSessionRepository,
    private val backlogTaskChangeService: BacklogTaskChangeService,
    private val backlogTaskRepository: BacklogTaskRepository,
    private val userCrypto: UserCryptoService,
    private val userSettingsService: UserSettingsService,
    private val clock: Clock,
) {

    @Transactional
    fun startSession(
        userId: UUID,
        weekStart: LocalDate,
        conversationId: UUID? = null,
    ): PlanningSession {
        val active = planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.ACTIVE)
        if (active != null && active.weekStart == weekStart) return active.toDomain(userCrypto)

        // NB: carry-over reschedule counts are bumped at finalize time (see
        // [bumpRescheduleCountsForCarriedOverTasks]), not here — starting a session must have no
        // effect on the existing plan so it can be cleanly abandoned.
        return planningSessionRepository.save(PlanningSessionEntity().apply {
            this.userId = userId
            this.conversationId = conversationId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = clock.instant()
            this.weekStart = weekStart
        }).toDomain(userCrypto)
    }

    @Transactional(readOnly = true)
    fun findSessionForWeek(userId: UUID, weekStart: LocalDate): PlanningSession? =
        planningSessionRepository
            .findFirstByUserIdAndWeekStartOrderByStartedAtDesc(userId, weekStart)
            ?.toDomain(userCrypto)

    @Transactional
    fun completeSession(userId: UUID, sessionId: UUID, summary: String?): PlanningSession =
        endSession(userId, sessionId, PlanningSessionStatus.COMPLETED, summary)

    /**
     * Bumps the reschedule count for tasks scheduled in the plan immediately preceding [finalizingWeek]
     * that were never marked done — they're being carried into the newly finalized plan. Call at
     * finalize time (before the new plan is written), NOT at session start, so that starting and then
     * abandoning a session leaves the existing plan and its task stats untouched.
     *
     * Resolving "previous" by week (rather than by globally-latest completed) keeps carry-over correct
     * when weeks are planned out of order — e.g. finalizing this week after next week was already
     * planned must still bump from *last* week, not from the future plan.
     */
    @Transactional
    fun bumpRescheduleCountsForCarriedOverTasks(userId: UUID, finalizingWeek: LocalDate) {
        val previous = planningSessionRepository
            .findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
                userId, PlanningSessionStatus.COMPLETED, finalizingWeek,
            )
        if (previous?.id != null) {
            backlogTaskRepository.incrementRescheduleCountForUnfinishedTasks(userId, previous.id!!)
        }
    }

    @Transactional
    fun abandonSession(userId: UUID, sessionId: UUID): PlanningSession =
        endSession(userId, sessionId, PlanningSessionStatus.ABANDONED, summary = null)

    private fun endSession(
        userId: UUID,
        sessionId: UUID,
        status: PlanningSessionStatus,
        summary: String?,
    ): PlanningSession {
        val session = planningSessionRepository.findByIdAndUserId(sessionId, userId)
            ?: throw NoSuchElementException("Planning session $sessionId not found")
        session.status = status
        session.endedAt = clock.instant()
        if (summary != null) session.summary = userCrypto.encrypt(userId, summary)
        return planningSessionRepository.save(session).toDomain(userCrypto)
    }

    @Transactional
    fun updateSummary(sessionId: UUID, summary: String?) {
        val session = planningSessionRepository.findById(sessionId).orElseThrow()
        session.summary = userCrypto.encrypt(session.userId!!, summary)
        planningSessionRepository.save(session)
    }

    @Transactional(readOnly = true)
    fun findById(userId: UUID, sessionId: UUID): PlanningSession? =
        planningSessionRepository.findByIdAndUserId(sessionId, userId)?.toDomain(userCrypto)

    @Transactional(readOnly = true)
    fun findActiveSession(userId: UUID): PlanningSession? =
        planningSessionRepository
            .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, PlanningSessionStatus.ACTIVE)
            ?.toDomain(userCrypto)

    /**
     * The start date (in the user's zone and configured week-start day) of the calendar week that
     * contains today. The anchor for "the current week's plan".
     */
    @Transactional(readOnly = true)
    fun currentWeekStart(userId: UUID): LocalDate {
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.ofInstant(clock.instant(), zone)
        return WeekResolver.resolveWeekStart(
            today, WeekResolver.parseWeekStartDay(settings.weekStartDay), WeekOffset.CURRENT,
        )
    }

    /**
     * The COMPLETED (finalized) plan for a specific week, or null if that week was never planned.
     * Plans are per-week: an in-progress (ACTIVE) session is deliberately NOT returned (planned tasks
     * are only written at submit), and ABANDONED sessions never count.
     */
    @Transactional(readOnly = true)
    fun findPlanForWeek(userId: UUID, weekStart: LocalDate): PlanningSession? =
        planningSessionRepository
            .findFirstByUserIdAndWeekStartAndStatusOrderByStartedAtDesc(
                userId, weekStart, PlanningSessionStatus.COMPLETED,
            )
            ?.toDomain(userCrypto)

    /**
     * The plan to treat as "the user's current weekly plan" in the UI: the finalized plan for the
     * week containing today. Unlike the old "most recently completed" definition this stays anchored
     * to the present week, so finalizing a plan for a *different* week (e.g. next week) no longer
     * hides the current week's plan. Returns null when the current week hasn't been planned.
     */
    @Transactional(readOnly = true)
    fun findCurrentPlan(userId: UUID): PlanningSession? =
        findPlanForWeek(userId, currentWeekStart(userId))

    /**
     * Returns the session whose summary should be prepended to the prompt for the week starting at
     * [beforeWeek] — the most recent COMPLETED plan for an *earlier* week. Resolving by week (not by
     * globally-latest completed) keeps the "previous session" honest when weeks are planned out of
     * order. Skips ABANDONED sessions.
     */
    @Transactional(readOnly = true)
    fun findPreviousSummarizableSession(userId: UUID, beforeWeek: LocalDate): PlanningSession? =
        planningSessionRepository
            .findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
                userId, PlanningSessionStatus.COMPLETED, beforeWeek,
            )
            ?.toDomain(userCrypto)

    /**
     * Computes the diff of backlog task changes between the plan immediately preceding [beforeWeek]
     * and now. If no prior session exists the result is empty.
     */
    @Transactional(readOnly = true)
    fun diffSincePreviousSession(userId: UUID, beforeWeek: LocalDate): TaskChangeSummary {
        val previous = findPreviousSummarizableSession(userId, beforeWeek)
            ?: return TaskChangeSummary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0)
        val since = previous.endedAt ?: previous.startedAt
        return backlogTaskChangeService.summarizeSince(userId, since)
    }
}
