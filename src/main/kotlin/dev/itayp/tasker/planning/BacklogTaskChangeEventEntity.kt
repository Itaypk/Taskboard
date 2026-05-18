package dev.itayp.tasker.planning

import dev.itayp.tasker.model.TaskStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "backlog_task_change_event")
open class BacklogTaskChangeEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

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
    var taskTitleSnapshot: String? = null

    @Column(name = "planning_session_id")
    var planningSessionId: UUID? = null

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant? = null
}
