package dev.itayp.tasker.notification

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.notification.ReminderDeliveryResolver.ReminderContext
import dev.itayp.tasker.service.BacklogTaskService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Delivers a due slot reminder to the user's channel and records its terminal status. This is the
 * sole subscriber of [SlotReminderDueEvent] — the one seam between the trigger ([NotificationScheduler])
 * and actual delivery, replacing Phase 1's no-op log listener.
 *
 * Owns every terminal transition (`EXPIRED` / `SKIPPED` / `SENT` / `FAILED`) so a row is never marked
 * sent before delivery actually succeeds. A transient send failure leaves the row `PENDING`, retried by
 * the next poll until it succeeds, hits [MAX_ATTEMPTS] (`FAILED`), or its slot start passes (`EXPIRED`).
 */
@Component
class SlotReminderDispatcher(
    private val repository: ScheduledNotificationRepository,
    private val deliveryResolver: ReminderDeliveryResolver,
    private val backlogTaskService: BacklogTaskService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SlotReminderDispatcher::class.java)

    @EventListener
    @Transactional
    fun on(event: SlotReminderDueEvent) {
        val notification = repository.findById(event.notificationId).orElse(null) ?: return
        // Idempotency guard: only act on rows still awaiting delivery.
        if (notification.status != NotificationStatus.PENDING) return

        val now = clock.instant()
        val slotStart = runCatching { OffsetDateTime.parse(event.slotStartIso).toInstant() }.getOrNull()
        if (slotStart == null || !now.isBefore(slotStart)) {
            // A "15-min-before" nudge is useless once the slot has started (also absorbs downtime).
            finish(notification, NotificationStatus.EXPIRED)
            return
        }

        val ctx = deliveryResolver.resolve(event.userId)
            ?: return skip(notification, "not eligible")
        val task = backlogTaskService.findTask(event.userId, event.backlogTaskId)
            ?: return skip(notification, "task no longer exists")

        val text = render(ctx, task.title, event.slotStartIso)
        try {
            ctx.channel.send(ChannelMessage.Text(text))
            notification.status = NotificationStatus.SENT
            notification.sentAt = now
            repository.save(notification)
            count("success")
            log.info("Delivered slot reminder {} to user {}", notification.id, event.userId)
        } catch (e: Exception) {
            notification.attempts += 1
            if (notification.attempts >= MAX_ATTEMPTS) {
                notification.status = NotificationStatus.FAILED
                log.error(
                    "Slot reminder {} failed after {} attempt(s); giving up",
                    notification.id, notification.attempts, e,
                )
            } else {
                log.warn(
                    "Slot reminder {} delivery failed (attempt {}); will retry",
                    notification.id, notification.attempts, e,
                )
            }
            repository.save(notification)
            count("failure")
        }
    }

    private fun render(ctx: ReminderContext, title: String, slotStartIso: String): String {
        val localTime = OffsetDateTime.parse(slotStartIso)
            .atZoneSameInstant(ctx.zone)
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ctx.locale))
        val escapedTitle = ctx.channel.formatter.escape(title)
        return messageSource.getMessage("notification.slot_reminder", arrayOf(escapedTitle, localTime), ctx.locale)
    }

    private fun skip(notification: ScheduledNotificationEntity, reason: String) {
        finish(notification, NotificationStatus.SKIPPED)
        count("skipped")
        log.debug("Skipped slot reminder {}: {}", notification.id, reason)
    }

    private fun finish(notification: ScheduledNotificationEntity, status: NotificationStatus) {
        notification.status = status
        repository.save(notification)
    }

    private fun count(outcome: String) {
        meterRegistry.counter(
            "tasker.notification.sent",
            "type", "slot_reminder",
            "channel", "telegram",
            "outcome", outcome,
        ).increment()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
