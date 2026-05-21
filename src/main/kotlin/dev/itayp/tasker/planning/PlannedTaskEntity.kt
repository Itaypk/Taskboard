package dev.itayp.tasker.planning

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "planned_task")
open class PlannedTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "session_id", nullable = false)
    var sessionId: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "backlog_task_id")
    var backlogTaskId: UUID? = null

    @Column(nullable = false)
    var title: String? = null

    @Column(columnDefinition = "TEXT")
    var notes: String? = null

    @Column(nullable = false)
    var position: Int = 0
}
