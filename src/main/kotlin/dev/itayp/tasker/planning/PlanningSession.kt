package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.UserCryptoService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Plaintext view of [PlanningSessionEntity]. PlanningSessionService produces this
 * from the entity via UserCryptoService.
 *
 * The plan's schedule is not part of it: that lives in `planned_task`, and anything that shows the
 * plan renders it from there ([PlanRecapRenderer]), so it can never drift from what's scheduled.
 * [weekNote] and [revisionNotes] hold only what the assistant learned that the data can't say.
 */
data class PlanningSession(
    val id: UUID,
    val userId: UUID,
    val conversationId: UUID?,
    val status: PlanningSessionStatus,
    val startedAt: Instant,
    val weekStart: LocalDate,
    val endedAt: Instant?,
    val weekNote: String?,
    val revisionNotes: List<RevisionNote> = emptyList(),
) {
    /** True when there's any assistant-written note to show alongside the schedule. */
    val hasNotes: Boolean get() = !weekNote.isNullOrBlank() || revisionNotes.isNotEmpty()
}

/** One revise session's note on what changed and why. Appended, never edited. */
data class RevisionNote(
    val at: Instant,
    val text: String,
)

fun PlanningSessionEntity.toDomain(crypto: UserCryptoService): PlanningSession {
    val ownerId = userId ?: throw IllegalStateException("PlanningSessionEntity must have userId")
    return PlanningSession(
        id = id ?: throw IllegalStateException("PlanningSessionEntity must have id"),
        userId = ownerId,
        conversationId = conversationId,
        status = status,
        startedAt = startedAt ?: throw IllegalStateException("PlanningSessionEntity must have startedAt"),
        weekStart = weekStart ?: throw IllegalStateException("PlanningSessionEntity must have weekStart"),
        endedAt = endedAt,
        weekNote = crypto.decrypt(ownerId, weekNote)?.takeIf { it.isNotBlank() },
        revisionNotes = RevisionNotesCodec.decode(crypto.decrypt(ownerId, revisionNotes)),
    )
}
