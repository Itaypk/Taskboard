package dev.itayp.tasker.notification

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Single-instance poller for due notifications. Polling (rather than a per-row [java.util.concurrent.ScheduledFuture])
 * fits one-off, slot-specific fire times and is naturally restart-safe: the queue lives in
 * `scheduled_notification`, so a restart simply resumes from the PENDING rows. The user accepts that
 * "close enough" timing is fine, so a ~1-minute cadence is sufficient.
 *
 * The poller's only job is to find due rows and emit [SlotReminderDueEvent]; [SlotReminderDispatcher]
 * (the sole subscriber) performs delivery and owns every terminal status, so a row is never recorded
 * as sent before it actually is. It is deliberately *not* transactional: events are published
 * synchronously and the dispatcher writes the outcome in its own transaction, so each row reaches a
 * terminal/retry state before the next event is emitted. A still-`PENDING` row (a transient delivery
 * failure) is simply picked up again on the next poll.
 */
@Component
class NotificationScheduler(
    private val repository: ScheduledNotificationRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(NotificationScheduler::class.java)

    @Scheduled(fixedDelay = POLL_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
    fun poll() {
        val now = clock.instant()
        val due = repository.findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
            NotificationStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE),
        )
        if (due.isEmpty()) return

        var fired = 0
        for (notification in due) {
            try {
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
                fired++
            } catch (e: Exception) {
                log.error("Failed to fire notification {}", notification.id, e)
            }
        }
        if (fired > 0) {
            log.info("Notification poll: fired={}", fired)
        }
    }

    companion object {
        private const val POLL_INTERVAL_SECONDS = 60L
        private const val BATCH_SIZE = 100
    }
}
