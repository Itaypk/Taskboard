package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.TagColor
import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.util.*

@Entity
@Table(name = "backlog_task_tag")
open class BacklogTaskTagEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "board_id")
    var boardId: UUID? = null

    @Column
    var label: String? = null

    @Column
    @Enumerated(EnumType.STRING)
    var colorId: TagColor? = null

    @Column
    var description: String? = null
}
