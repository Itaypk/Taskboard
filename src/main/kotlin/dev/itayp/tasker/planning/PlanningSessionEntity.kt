package dev.itayp.tasker.planning

import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Entity
@Table(name = "planning_session")
open class PlanningSessionEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "conversation_id")
    var conversationId: UUID? = null

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: PlanningSessionStatus = PlanningSessionStatus.ACTIVE

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant? = null

    @Column(name = "week_start", nullable = false)
    var weekStart: LocalDate? = null

    @Column(name = "ended_at")
    var endedAt: Instant? = null

    @Column
    var summary: ByteArray? = null
}
