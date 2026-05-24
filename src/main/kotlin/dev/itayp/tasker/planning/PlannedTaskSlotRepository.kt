package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlannedTaskSlotRepository : JpaRepository<PlannedTaskSlotEntity, UUID> {
    fun findAllByPlannedTaskIdIn(taskIds: Collection<UUID>): List<PlannedTaskSlotEntity>

    fun deleteAllByPlannedTaskId(plannedTaskId: UUID)
}
