package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "backlog_task")
open class BacklogTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column
    var userId: String? = null

    @Column
    var title: String? = null

    @Column(length = 10_000)
    var description: String? = null

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
    var tags: Set<BacklogTaskTagEntity>? = null

    @Column
    var createdAt: Instant? = null

    @Column
    var updatedAt: Instant? = null
}