package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.TaskPriority
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
}