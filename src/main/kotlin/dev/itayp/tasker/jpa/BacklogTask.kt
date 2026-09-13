package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.RecurrenceKind
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskRecurrence
import dev.itayp.tasker.model.TaskStatus
import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Entity
@Table(name = "backlog_task")
class BacklogTaskEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "board_id")
    var boardId: UUID? = null

    /** Member who has claimed/been assigned this task; null = unassigned. Written from a later phase. */
    @Column(name = "assignee_user_id")
    var assigneeUserId: UUID? = null

    @Column
    var title: ByteArray? = null

    @Column
    var description: ByteArray? = null

    @Column
    var url: String? = null

    @Column
    @Enumerated(EnumType.STRING)
    var priority: TaskPriority? = null

    @Column
    var deadline: LocalDate? = null

    @Column
    var estimatedMinutes: Int? = null

    @Column
    @Enumerated(EnumType.STRING)
    var status: TaskStatus? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: BacklogTaskCategoryEntity? = null

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "backlog_task_tags",
        joinColumns = [JoinColumn(name = "task_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")]
    )
    var tags: MutableSet<BacklogTaskTagEntity> = mutableSetOf()

    @Column(name = "sort_key")
    var sortKey: String? = null

    @Column
    var createdAt: Instant? = null

    @Column
    var updatedAt: Instant? = null

    @Column(name = "reschedule_count", nullable = false)
    var rescheduleCount: Int = 0

    @Column(name = "last_scheduled_in_session_id")
    var lastScheduledInSessionId: UUID? = null

    @Column(name = "relevant_from")
    var relevantFrom: LocalDate? = null

    /** Part of the seeded tutorial backlog: immutable (client-side), one-click clearable, excluded from the engagement signal. */
    @Column(name = "tutorial", nullable = false)
    var tutorial: Boolean = false

    /** User opt-out: when true the task is withheld from every AI-assistant read path (planner slate, backlog search, quick-add sampling). Independent of priority. */
    @Column(name = "hidden_from_assistant", nullable = false)
    var hiddenFromAssistant: Boolean = false

    /** Null = not recurring. The remaining rule columns are meaningful only per kind. */
    @Column(name = "recurrence_kind")
    @Enumerated(EnumType.STRING)
    var recurrenceKind: RecurrenceKind? = null

    @Column(name = "recurrence_every")
    var recurrenceEvery: Int? = null

    @Column(name = "recurrence_day")
    var recurrenceDay: Int? = null

    @Column(name = "recurrence_month")
    var recurrenceMonth: Int? = null

    @Column(name = "due_within_days")
    var dueWithinDays: Int? = null

    /** Local date of the latest completion — on a recurring original and on each completed copy. */
    @Column(name = "last_completed_on")
    var lastCompletedOn: LocalDate? = null

    /** Set on a completed-occurrence copy: the recurring original it was saved from. */
    @Column(name = "recurrence_source_id")
    var recurrenceSourceId: UUID? = null

    var recurrence: TaskRecurrence?
        get() = recurrenceKind?.let { TaskRecurrence(it, recurrenceEvery, recurrenceDay, recurrenceMonth, dueWithinDays) }
        set(value) {
            recurrenceKind = value?.kind
            recurrenceEvery = value?.every
            recurrenceDay = value?.day
            recurrenceMonth = value?.month
            dueWithinDays = value?.dueWithinDays
        }
}