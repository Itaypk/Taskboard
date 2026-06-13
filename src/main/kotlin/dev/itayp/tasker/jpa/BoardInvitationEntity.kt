package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A pending board invitation — an email capability token (mirrors [EmailLoginTokenEntity]).
 *
 * Only the email *hash* is stored: the plaintext came from the inviter and is never persisted. The
 * token is the authorization to accept (capability-URL model); whoever presents a valid token may
 * accept with whatever account they're signed into. Status is derived, not stored — see
 * `BoardInvitationService`:
 *   pending   = consumedAt == null && revokedAt == null && now <= expiresAt
 *   accepted  = consumedAt != null (with acceptedByUserId set)
 *   revoked   = revokedAt != null (owner cancel or superseded by re-invite)
 *   expired   = now > expiresAt
 */
@Entity
@Table(name = "board_invitation")
class BoardInvitationEntity {
    @Id
    var id: UUID? = null

    @Column(name = "board_id", nullable = false)
    var boardId: UUID? = null

    @Column(name = "email_hash", nullable = false, length = 64)
    var emailHash: String? = null

    @Column(name = "token", nullable = false, length = 64)
    var token: String? = null

    /** The OWNER who sent the invite; nulled (not deleted) if their account is removed. */
    @Column(name = "invited_by_user_id")
    var invitedByUserId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "accepted_by_user_id")
    var acceptedByUserId: UUID? = null
}
