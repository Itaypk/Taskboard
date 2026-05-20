package dev.itayp.tasker.planning

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "planning_session")
open class PlanningSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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

    @Column(length = 4000)
    var summary: String? = null
}
