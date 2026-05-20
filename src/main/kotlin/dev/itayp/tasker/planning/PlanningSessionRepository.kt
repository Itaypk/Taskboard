package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface PlanningSessionRepository : JpaRepository<PlanningSessionEntity, UUID> {

    fun findByIdAndUserId(id: UUID, userId: UUID): PlanningSessionEntity?

    fun findFirstByUserIdAndStatusOrderByStartedAtDesc(
        userId: UUID,
        status: PlanningSessionStatus,
    ): PlanningSessionEntity?

    fun findFirstByUserIdAndWeekStartOrderByStartedAtDesc(
        userId: UUID,
        weekStart: LocalDate,
    ): PlanningSessionEntity?

    fun findAllByUserIdOrderByStartedAtDesc(userId: UUID): List<PlanningSessionEntity>
}
