package dev.itayp.tasker.model

import java.time.Instant
import java.util.UUID

/**
 * Owner-facing view of a pending invitation. Deliberately address-free: we never store the plaintext
 * email (only its hash), and the owner just typed it — so the list shows dates, not the recipient
 * (docs/BOARD-SHARING-PHASE2.md).
 */
data class PendingInvitation(
    val id: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** Invitee-facing preview shown on the accept screen before they commit. The token is the authorization. */
data class InvitationPreview(
    val boardName: String,
    val inviterName: String,
    val expiresAt: Instant,
)

/** Result of accepting an invitation: the board the acceptor just joined. */
data class AcceptedInvitation(
    val boardId: UUID,
    val boardName: String,
)
