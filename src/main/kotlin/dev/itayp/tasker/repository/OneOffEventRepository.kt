package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.OneOffEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface OneOffEventRepository : JpaRepository<OneOffEventEntity, UUID> {

    fun findAllByUserIdAndStartsAtBetweenAndCancelledAtIsNullOrderByStartsAtAsc(
        userId: UUID,
        from: Instant,
        to: Instant,
    ): List<OneOffEventEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): OneOffEventEntity?
}
