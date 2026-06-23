package dev.itayp.tasker.notification

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Single-instance poller for due notifications. Polling (rather than a per-row [java.util.concurrent.ScheduledFuture])
 * fits one-off, slot-specific fire times and is naturally restart-safe: the queue lives in
 * `scheduled_notification`, so a restart simply resumes from the PENDING rows. The user accepts that
 * "close enough" timing is fine, so a ~1-minute cadence is sufficient.
 *
 * Each due row is handled independently and fail-soft. A reminder whose slot start has already passed
 * is expired rather than fired — a "before-start" nudge after the fact is useless.
 */
@Component
class NotificationScheduler(
    private val repository: ScheduledNotificationRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(NotificationScheduler::class.java)

    @Scheduled(fixedDelay = POLL_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
    @Transactional
    fun poll() {
        val now = clock.instant()
        val due = repository.findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
            NotificationStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE),
        )
        if (due.isEmpty()) return

        var fired = 0
        var expired = 0
        for (notification in due) {
            try {
                val slotStart = runCatching { OffsetDateTime.parse(notification.slotStartIso).toInstant() }.getOrNull()
                if (slotStart == null || !now.isBefore(slotStart)) {
                    notification.status = NotificationStatus.EXPIRED
                    repository.save(notification)
                    expired++
                    continue
                }
                eventPublisher.publishEvent(
                    SlotReminderDueEvent(
                        notificationId = notification.id!!,
                        userId = notification.userId!!,
                        sessionId = notification.sessionId!!,
                        backlogTaskId = notification.backlogTaskId!!,
                        slotStartIso = notification.slotStartIso!!,
                        slotEndIso = notification.slotEndIso!!,
                    ),
                )
                notification.status = NotificationStatus.SENT
                notification.sentAt = now
                repository.save(notification)
                fired++
            } catch (e: Exception) {
                log.error("Failed to fire notification {}", notification.id, e)
            }
        }
        if (fired > 0 || expired > 0) {
            log.info("Notification poll: fired={} expired={}", fired, expired)
        }
    }

    companion object {
        private const val POLL_INTERVAL_SECONDS = 60L
        private const val BATCH_SIZE = 100
    }
}
