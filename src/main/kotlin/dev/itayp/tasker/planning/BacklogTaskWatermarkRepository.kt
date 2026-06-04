package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface BacklogTaskWatermarkRepository : JpaRepository<BacklogTaskWatermarkEntity, UUID> {

    fun existsByUserIdAndTasksChangedAtGreaterThanEqual(userId: UUID, tasksChangedAt: Instant): Boolean
}
