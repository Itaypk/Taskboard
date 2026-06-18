package dev.itayp.tasker.planning

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.util.*

@Entity
@Table(name = "planned_task_slot")
class PlannedTaskSlotEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "planned_task_id", nullable = false)
    var plannedTaskId: UUID? = null

    @Column(name = "start_iso", nullable = false)
    var startIso: String? = null

    @Column(name = "end_iso", nullable = false)
    var endIso: String? = null

    var label: String? = null
}
