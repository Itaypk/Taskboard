package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.CategoryColor
import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.util.*

@Entity
@Table(name = "backlog_task_category")
open class BacklogTaskCategoryEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "board_id")
    var boardId: UUID? = null

    @Column
    var label: String? = null

    @Column
    @Enumerated(EnumType.STRING)
    var swatchId: CategoryColor? = null
}
