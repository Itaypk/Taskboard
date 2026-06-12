package dev.itayp.tasker.model

import java.time.Instant
import java.util.UUID

/** A board as seen by one of its members: decrypted name plus that member's role. */
data class BoardSummary(
    val id: UUID,
    val name: String,
    val role: BoardRole,
    val createdAt: Instant,
)

/** One member of a board, with a server-resolved display name (see [dev.itayp.tasker.service.MemberDisplayNameResolver]). */
data class BoardMember(
    val userId: UUID,
    val role: BoardRole,
    val joinedAt: Instant,
    val displayName: String,
)
