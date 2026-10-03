package dev.itayp.tasker.notification.digest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.LocalDate
import java.util.UUID

/**
 * One user's mute of a due task in the daily digest. Per user rather than a task column, because a
 * task on a shared board belongs to the board and one member's mute must not silence it for others.
 *
 * A mute only holds while the task's deadline still equals [deadline]: moving the deadline (or a
 * recurring task rolling forward) lifts it without anyone having to remember it exists.
 */
@Entity
@Table(name = "deadline_reminder_mute")
class DeadlineReminderMuteEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "backlog_task_id", nullable = false)
    var backlogTaskId: UUID? = null

    /** The deadline the user muted. */
    @Column(name = "deadline", nullable = false)
    var deadline: LocalDate? = null

    /** Exclusive: the task is listed again from this date on. */
    @Column(name = "muted_until", nullable = false)
    var mutedUntil: LocalDate? = null

    /** True when this mute still silences a task whose current deadline is [taskDeadline]. */
    fun silences(taskDeadline: LocalDate, today: LocalDate): Boolean =
        deadline == taskDeadline && today.isBefore(mutedUntil)
}
