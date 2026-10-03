package dev.itayp.tasker.notification.digest

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeadlineReminderMuteRepository : JpaRepository<DeadlineReminderMuteEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<DeadlineReminderMuteEntity>

    fun findAllByUserIdAndBacklogTaskIdIn(userId: UUID, backlogTaskIds: Collection<UUID>): List<DeadlineReminderMuteEntity>

    /** Returns how many mutes were removed. */
    fun deleteByUserId(userId: UUID): Long
}
