package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlannedTaskRepository : JpaRepository<PlannedTaskEntity, UUID> {
    fun findAllBySessionIdOrderByPosition(sessionId: UUID): List<PlannedTaskEntity>
}
