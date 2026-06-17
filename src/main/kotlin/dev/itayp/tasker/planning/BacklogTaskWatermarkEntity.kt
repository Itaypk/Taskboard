package dev.itayp.tasker.planning

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A single per-board change watermark, bumped on every backlog mutation
 * (create / edit / delete / reorder / schedule) plus tag and category edits. The sync
 * endpoint returns these timestamps so an open board tab can refetch only the entity
 * types that moved; because it's board-keyed, a member's edit refreshes other members' tabs.
 *
 * [tagsChangedAt] / [categoriesChangedAt] are nullable: a board that predates this signal
 * (or has had no tag/category change yet) simply has no value, which the client reads as
 * "unchanged".
 *
 * Distinct from [BacklogTaskChangeEventEntity]: that log records *semantic* lifecycle
 * events the planner needs (and which must outlive deleted tasks). This is a cheap,
 * O(1) "something changed" signal that also covers plain field edits and reorders —
 * changes the planner doesn't care about (current state is fully visible to it) but
 * an open board does.
 */
@Entity
@Table(name = "backlog_task_watermark")
open class BacklogTaskWatermarkEntity {
    @Id
    @Column(name = "board_id", nullable = false)
    var boardId: UUID? = null

    @Column(name = "tasks_changed_at", nullable = false)
    var tasksChangedAt: Instant? = null

    @Column(name = "tags_changed_at")
    var tagsChangedAt: Instant? = null

    @Column(name = "categories_changed_at")
    var categoriesChangedAt: Instant? = null
}
