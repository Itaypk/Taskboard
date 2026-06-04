package dev.itayp.tasker.ai.usage

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AiUsageEventRepository : JpaRepository<AiUsageEventEntity, UUID> {

    /** Number of calls a user has made since [since]. */
    fun countByUserIdAndCreatedAtGreaterThanEqual(userId: UUID, since: Instant): Long

    /**
     * Total tokens a user has consumed since [since] — the query a per-user rate limiter will use.
     * Returns 0 when the user has no usage in the window.
     */
    @Query(
        """
        SELECT COALESCE(SUM(e.totalTokens), 0)
        FROM AiUsageEventEntity e
        WHERE e.userId = :userId AND e.createdAt >= :since
        """
    )
    fun sumTotalTokensByUserIdSince(userId: UUID, since: Instant): Long
}
