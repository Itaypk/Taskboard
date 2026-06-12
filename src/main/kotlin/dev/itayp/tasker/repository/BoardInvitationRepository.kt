package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BoardInvitationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface BoardInvitationRepository : JpaRepository<BoardInvitationEntity, UUID> {

    fun findByToken(token: String): BoardInvitationEntity?

    /** Pending (not consumed, not revoked) invitations for a board — the service filters expiry. */
    fun findAllByBoardIdAndConsumedAtIsNullAndRevokedAtIsNull(boardId: UUID): List<BoardInvitationEntity>

    /** Pending invitations for a (board, email) pair — used to revoke a prior invite on re-invite. */
    fun findAllByBoardIdAndEmailHashAndConsumedAtIsNullAndRevokedAtIsNull(
        boardId: UUID,
        emailHash: String,
    ): List<BoardInvitationEntity>

    /** Per-board send rate limit. */
    fun countByBoardIdAndCreatedAtAfter(boardId: UUID, since: Instant): Long

    /** Per-inviter send rate limit. */
    fun countByInvitedByUserIdAndCreatedAtAfter(invitedByUserId: UUID, since: Instant): Long

    @Modifying
    @Query("UPDATE BoardInvitationEntity i SET i.invitedByUserId = NULL WHERE i.invitedByUserId = :userId")
    fun nullInviterForUser(@Param("userId") userId: UUID): Int

    @Modifying
    @Query("DELETE FROM BoardInvitationEntity i WHERE i.expiresAt < :cutoff")
    fun deleteAllExpired(@Param("cutoff") cutoff: Instant): Int
}
