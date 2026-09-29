package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {

    fun findByTelegramId(telegramId: Long): UserEntity?

    fun findByEmailHash(emailHash: String): UserEntity?

    fun findByEmailVerificationTokenHash(tokenHash: String): UserEntity?

    /**
     * Unclaimed accounts eligible for automatic reclamation. `claimed = false` is the hard guard;
     * the engagement state only selects which inactivity cutoff applies — a created-but-unengaged
     * account is swept on the short cutoff, an engaged one gets the longer cutoff.
     */
    @Query(
        """
        SELECT u FROM UserEntity u
        WHERE u.claimed = false AND (
            (u.engagedAt IS NULL     AND u.lastActiveAt < :shortCutoff) OR
            (u.engagedAt IS NOT NULL AND u.lastActiveAt < :longCutoff)
        )
        """,
    )
    fun findSweepableAccounts(shortCutoff: Instant, longCutoff: Instant): List<UserEntity>

    @Modifying
    @Query("UPDATE UserEntity u SET u.lastActiveAt = :now WHERE u.id = :userId")
    fun touchLastActiveAt(@Param("userId") userId: UUID, @Param("now") now: Instant)

    /** Idempotent one-way engagement stamp; only writes the first time (engaged_at still null). */
    @Modifying
    @Query("UPDATE UserEntity u SET u.engagedAt = :now WHERE u.id = :userId AND u.engagedAt IS NULL")
    fun stampEngagedAt(@Param("userId") userId: UUID, @Param("now") now: Instant)

    /** Idempotent; returns 1 only on the transition from unreachable, so callers act once. */
    @Modifying
    @Query("UPDATE UserEntity u SET u.telegramChatReadyAt = :now WHERE u.id = :userId AND u.telegramChatReadyAt IS NULL")
    fun stampTelegramChatReady(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

    /** Idempotent; returns 1 only on the transition from reachable, so callers act once. */
    @Modifying
    @Query("UPDATE UserEntity u SET u.telegramChatReadyAt = NULL WHERE u.id = :userId AND u.telegramChatReadyAt IS NOT NULL")
    fun clearTelegramChatReady(@Param("userId") userId: UUID): Int

    fun countByClaimed(claimed: Boolean): Long

    fun countByClaimedAndEngagedAtNotNull(claimed: Boolean): Long

    fun countByClaimedAndLastActiveAtAfter(claimed: Boolean, cutoff: Instant): Long
}
