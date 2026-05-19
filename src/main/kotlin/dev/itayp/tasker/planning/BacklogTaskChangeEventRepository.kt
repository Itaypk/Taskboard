package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface BacklogTaskChangeEventRepository : JpaRepository<BacklogTaskChangeEventEntity, UUID> {

    fun findAllByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
        userId: UUID,
        occurredAt: Instant,
    ): List<BacklogTaskChangeEventEntity>

    fun findAllByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(
        userId: UUID,
        from: Instant,
        to: Instant,
    ): List<BacklogTaskChangeEventEntity>

    fun existsByUserIdAndOccurredAtGreaterThanEqual(userId: UUID, occurredAt: Instant): Boolean
}
