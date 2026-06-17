package dev.itayp.tasker.planning

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A single per-user "plan changed" watermark, bumped whenever the user's finalized plan changes
 * (planner finalization/revision, adding a task to the current plan, or unscheduling one). The sync
 * endpoint returns it so an open tab refetches `/plans/current` only when it actually moved — which
 * also lets a plan finalized over Telegram surface in an already-open tab.
 *
 * Plan state is user-scoped (not board-scoped), so this is keyed by user rather than board.
 */
@Entity
@Table(name = "plan_watermark")
open class PlanWatermarkEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "plan_changed_at", nullable = false)
    var planChangedAt: Instant? = null
}
