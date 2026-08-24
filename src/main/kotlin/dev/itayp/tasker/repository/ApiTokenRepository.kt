package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.ApiTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ApiTokenRepository : JpaRepository<ApiTokenEntity, UUID> {

    /** Authentication lookup. The hash is unique, so this is at most one row. */
    fun findByTokenHash(tokenHash: String): ApiTokenEntity?

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<ApiTokenEntity>

    fun countByUserIdAndRevokedAtIsNull(userId: UUID): Long

    fun findByIdAndUserId(id: UUID, userId: UUID): ApiTokenEntity?

    /**
     * Stamps last-use without loading/flushing the entity — this runs on the read path, so it
     * must stay cheap. Callers throttle it (see `ApiTokenService`) so a busy token doesn't turn
     * every GET into a write.
     */
    @Modifying
    @Query("UPDATE ApiTokenEntity t SET t.lastUsedAt = :at WHERE t.id = :id")
    fun touchLastUsed(@Param("id") id: UUID, @Param("at") at: Instant)
}
