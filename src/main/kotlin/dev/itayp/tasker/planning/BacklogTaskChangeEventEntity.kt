package dev.itayp.tasker.planning

import dev.itayp.tasker.model.TaskStatus
import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.*

@Entity
@Table(name = "backlog_task_change_event")
open class BacklogTaskChangeEventEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "board_id", nullable = false)
    var boardId: UUID? = null

    /**
     * Who performed the change. Scoping is by [boardId]; this is "who did it" on a shared board.
     * Nullable because the actor's account may be deleted while the board (and its history) survives.
     */
    @Column(name = "actor_user_id")
    var actorUserId: UUID? = null

    @Column(name = "task_id", nullable = false)
    var taskId: UUID? = null

    @Column(name = "change_type", nullable = false)
    @Enumerated(EnumType.STRING)
    var changeType: BacklogTaskChangeType? = null

    @Column(name = "previous_status")
    @Enumerated(EnumType.STRING)
    var previousStatus: TaskStatus? = null

    @Column(name = "new_status")
    @Enumerated(EnumType.STRING)
    var newStatus: TaskStatus? = null

    @Column(name = "task_title_snapshot")
    var taskTitleSnapshot: ByteArray? = null

    @Column(name = "planning_session_id")
    var planningSessionId: UUID? = null

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant? = null
}
