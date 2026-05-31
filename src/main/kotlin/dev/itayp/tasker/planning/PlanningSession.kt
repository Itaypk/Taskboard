package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.UserCryptoService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Plaintext view of [PlanningSessionEntity]. PlanningSessionService produces this
 * from the entity via UserCryptoService.
 */
data class PlanningSession(
    val id: UUID,
    val userId: UUID,
    val conversationId: UUID?,
    val status: PlanningSessionStatus,
    val startedAt: Instant,
    val weekStart: LocalDate,
    val endedAt: Instant?,
    val summary: String?,
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
        summary = crypto.decrypt(ownerId, summary),
    )
}
