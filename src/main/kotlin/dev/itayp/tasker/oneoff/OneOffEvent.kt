package dev.itayp.tasker.oneoff

import java.time.Instant
import java.util.UUID

/** Plaintext view of a persisted one-off event — what controllers/UI see. */
data class OneOffEvent(
    val id: UUID,
    val userId: UUID,
    val boardId: UUID,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val location: String?,
    val notes: String?,
    val icalUid: String,
    val cancelledAt: Instant?,
)
