package dev.itayp.tasker.notification

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ScheduledNotificationRepository : JpaRepository<ScheduledNotificationEntity, UUID> {

    /** Due rows for the poller, oldest fire time first; [Pageable] bounds the batch. */
    fun findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
        status: NotificationStatus,
        fireAt: Instant,
        pageable: Pageable,
    ): List<ScheduledNotificationEntity>

    /** Cancels still-pending reminders for slots that were removed/rescheduled out of a plan. */
    @Modifying
    @Query(
        "UPDATE ScheduledNotificationEntity n " +
            "SET n.status = dev.itayp.tasker.notification.NotificationStatus.CANCELLED " +
            "WHERE n.sessionId = :sessionId AND n.backlogTaskId = :backlogTaskId " +
            "AND n.slotStartIso = :slotStartIso " +
            "AND n.status = dev.itayp.tasker.notification.NotificationStatus.PENDING",
    )
    fun cancelPending(
        @Param("sessionId") sessionId: UUID,
        @Param("backlogTaskId") backlogTaskId: UUID,
        @Param("slotStartIso") slotStartIso: String,
    ): Int

    fun deleteByUserId(userId: UUID)
}
