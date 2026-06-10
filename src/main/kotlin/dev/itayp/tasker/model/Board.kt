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
