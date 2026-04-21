package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.CategoryColor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "backlog_task_category")
open class BacklogTaskCategoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column
    var userId: String? = null

    @Column
    var label: String? = null

    @Column
    @Enumerated(EnumType.STRING)
    var swatchId: CategoryColor? = null
}
