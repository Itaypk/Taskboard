package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface PlanningSessionRepository : JpaRepository<PlanningSessionEntity, UUID> {

    fun findByIdAndUserId(id: UUID, userId: UUID): PlanningSessionEntity?

    fun countByStatus(status: PlanningSessionStatus): Long

    fun countByUserIdAndStatus(userId: UUID, status: PlanningSessionStatus): Long

    fun findFirstByUserIdAndStatusOrderByStartedAtDesc(
        userId: UUID,
        status: PlanningSessionStatus,
    ): PlanningSessionEntity?

    fun findFirstByUserIdAndWeekStartOrderByStartedAtDesc(
        userId: UUID,
        weekStart: LocalDate,
    ): PlanningSessionEntity?

    /** The plan for a specific week in a given status — the week-keyed lookup behind "current plan". */
    fun findFirstByUserIdAndWeekStartAndStatusOrderByStartedAtDesc(
        userId: UUID,
        weekStart: LocalDate,
        status: PlanningSessionStatus,
    ): PlanningSessionEntity?

    /**
     * The most recent session (by week) in a given status whose week falls strictly before
     * [weekStart] — i.e. the plan immediately preceding the week being planned. Used so carry-over,
     * diffing, and the previous-summary all resolve relative to the target week rather than to the
     * globally-latest completed session (which may be a later week planned ahead of time).
     */
    fun findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
        userId: UUID,
        status: PlanningSessionStatus,
        weekStart: LocalDate,
    ): PlanningSessionEntity?

    fun findAllByUserIdOrderByStartedAtDesc(userId: UUID): List<PlanningSessionEntity>

    /**
     * All sessions in a status, latest week first (and latest session first within a week), so callers
     * can dedupe to one plan per week by keeping the first occurrence.
     */
    fun findAllByUserIdAndStatusOrderByWeekStartDescStartedAtDesc(
        userId: UUID,
        status: PlanningSessionStatus,
    ): List<PlanningSessionEntity>
}
