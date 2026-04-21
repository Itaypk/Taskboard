package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.TagColor
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
@Table(name = "backlog_task_tag")
open class BacklogTaskTagEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column
    var userId: String? = null

    @Column
    var label: String? = null

    @Column
    @Enumerated(EnumType.STRING)
    var colorId: TagColor? = null

    @Column
    var description: String? = null
}
