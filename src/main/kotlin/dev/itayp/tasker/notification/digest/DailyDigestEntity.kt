package dev.itayp.tasker.notification.digest

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A daily digest that was actually sent, with the due tasks it listed. Digest buttons carry this
 * row's id (`dig:<code>:<digestId>`), so a mute applies to exactly the tasks — at exactly the
 * deadlines — the user saw, not to a list recomputed at tap time.
 *
 * Identifiers and dates only; titles are decrypted at compose time and live only in the sent message.
 */
@Entity
@Table(name = "daily_digest")
class DailyDigestEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    /** The user's local date the digest was for. */
    @Column(name = "digest_date", nullable = false)
    var digestDate: LocalDate? = null

    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant? = null

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "daily_digest_due_task", joinColumns = [JoinColumn(name = "digest_id")])
    var dueTasks: MutableList<DigestDueTask> = mutableListOf()
}

/** A due task as listed in a sent digest: the task and the deadline shown for it. */
@Embeddable
class DigestDueTask(
    @Column(name = "backlog_task_id", nullable = false)
    var backlogTaskId: UUID? = null,

    @Column(name = "deadline", nullable = false)
    var deadline: LocalDate? = null,
)
