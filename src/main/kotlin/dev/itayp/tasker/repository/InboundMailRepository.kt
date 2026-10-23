package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.InboundMailEntity
import dev.itayp.tasker.jpa.InboundMailStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface InboundMailRepository : JpaRepository<InboundMailEntity, UUID> {

    fun existsByDedupKey(dedupKey: String): Boolean

    fun findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
        status: InboundMailStatus,
        now: Instant,
        pageable: Pageable,
    ): List<InboundMailEntity>

    /** Housekeeping: processed rows (DONE or FAILED) only live on as dedup keys, for a while. */
    @Modifying
    @Query("DELETE FROM InboundMailEntity m WHERE m.processedAt IS NOT NULL AND m.processedAt < :before")
    fun deleteProcessedBefore(@Param("before") before: Instant): Int
}
