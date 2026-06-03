package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlannedTaskRepository : JpaRepository<PlannedTaskEntity, UUID> {
    fun findAllBySessionIdOrderByPosition(sessionId: UUID): List<PlannedTaskEntity>

    fun findAllByUserIdAndBacklogTaskIdIn(userId: UUID, backlogTaskIds: Collection<UUID>): List<PlannedTaskEntity>

    fun findBySessionIdAndBacklogTaskId(sessionId: UUID, backlogTaskId: UUID): PlannedTaskEntity?

    fun countBySessionId(sessionId: UUID): Long
}
