package dev.itayp.tasker.planning

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Owns the per-user "plan changed" watermark backing the sync endpoint. Kept separate from the
 * board-keyed task/tag/category watermark because plan state is user-scoped.
 */
@Service
class PlanWatermarkService(
    private val repository: PlanWatermarkRepository,
    private val clock: Clock,
) {

    /** Marks the user's current plan as changed. Call from every plan write path. */
    @Transactional
    fun bump(userId: UUID) {
        val entity = repository.findById(userId).orElseGet {
            PlanWatermarkEntity().apply { this.userId = userId }
        }
        entity.planChangedAt = clock.instant()
        repository.save(entity)
    }

    /** The user's plan watermark, or null if no plan change has ever been recorded. */
    @Transactional(readOnly = true)
    fun read(userId: UUID): Instant? = repository.findById(userId).orElse(null)?.planChangedAt
}
