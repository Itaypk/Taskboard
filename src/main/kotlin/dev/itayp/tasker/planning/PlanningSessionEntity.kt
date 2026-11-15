package dev.itayp.tasker.planning

import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Entity
@Table(name = "planning_session")
class PlanningSessionEntity {
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

    /**
     * The week note, encrypted under the user's DEK: what the assistant learned about this week when
     * the plan was finalized. Written once; a revision never overwrites it. The column keeps its
     * historical name (it used to hold a prose summary that a revision replaced wholesale).
     */
    @Column(name = "summary")
    var weekNote: ByteArray? = null

    /**
     * Append-only notes from revise sessions: a JSON array of [RevisionNote], encrypted under the
     * user's DEK. Only [PlanningSessionService.appendRevisionNote] writes it.
     */
    @Column(name = "revision_notes")
    var revisionNotes: ByteArray? = null
}
