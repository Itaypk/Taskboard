package dev.itayp.tasker.notification

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
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
import java.util.Locale

/**
 * Delivers a due slot reminder to the user's channel and records its terminal status. This is the
 * sole subscriber of [SlotReminderDueEvent] — the one seam between the trigger ([NotificationScheduler])
 * and actual delivery.
 *
 * Phase 2b: the reminder now carries an interactive menu (ack / snooze / mark done) for *every*
 * notified user, and AI-enhanced users get an AI-generated message instead of the static template
 * (falling back to the template on any AI failure). Button taps are handled by [ReminderActionHandler].
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
    private val reminderMessageAgent: ReminderMessageAgent,
    private val aiAccessService: AiAccessService,
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
        // A snoozed row deliberately fires at its (later) fire time regardless of slot timing — snoozing
        // applies to the notification, not the slot — so the "slot already started" gate is skipped.
        if (!notification.snoozed && (slotStart == null || !now.isBefore(slotStart))) {
            // A "15-min-before" nudge is useless once the slot has started (also absorbs downtime).
            finish(notification, NotificationStatus.EXPIRED)
            return
        }

        val ctx = deliveryResolver.resolve(event.userId)
            ?: return skip(notification, "not eligible")
        val task = backlogTaskService.findTask(event.userId, event.backlogTaskId)
            ?: return skip(notification, "task no longer exists")

        // AI copy only when the user is opted in AND the task's board permits AI (a shared-board
        // co-member opt-out vetoes it). Any failure falls through to the static template below.
        val aiText = if (ctx.aiEnhanced && aiAccessService.isAiEnabledForBoard(task.boardId)) {
            reminderMessageAgent.generate(event.userId, task.title, task.description, event.slotStartIso, ctx.locale, ctx.zone)
        } else {
            null
        }
        val content = if (aiText != null) "ai" else "static"
        val prompt = aiText
            ?.let { ctx.channel.formatter.escape(it) }
            ?: renderStatic(ctx, task.title, event.slotStartIso, notification.snoozed)

        try {
            ctx.channel.send(ChannelMessage.Choice(prompt = prompt, options = menu(notification, ctx.locale)))
            notification.status = NotificationStatus.SENT
            notification.sentAt = now
            repository.save(notification)
            count("success", content)
            log.info("Delivered slot reminder {} to user {} (content={})", notification.id, event.userId, content)
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
            count("failure", content)
        }
    }

    /** The ack / snooze / mark-done buttons, each carrying self-describing callback data. */
    private fun menu(notification: ScheduledNotificationEntity, locale: Locale): List<ChoiceOption> {
        val id = notification.id!!
        return listOf(
            ChoiceOption(ReminderAction.ACK.callbackData(id), msg("notification.action.ack", locale)),
            ChoiceOption(ReminderAction.MARK_DONE.callbackData(id), msg("notification.action.done", locale)),
            ChoiceOption(ReminderAction.SNOOZE_HOUR.callbackData(id), msg("notification.action.snooze_hour", locale)),
            ChoiceOption(ReminderAction.SNOOZE_DAY.callbackData(id), msg("notification.action.snooze_day", locale)),
        )
    }

    private fun renderStatic(ctx: ReminderContext, title: String, slotStartIso: String, snoozed: Boolean): String {
        val escapedTitle = ctx.channel.formatter.escape(title)
        // A snoozed reminder's slot start is in the past, so its message drops the (now stale) time.
        if (snoozed) {
            return messageSource.getMessage("notification.slot_reminder.snoozed", arrayOf(escapedTitle), ctx.locale)
        }
        val localTime = OffsetDateTime.parse(slotStartIso)
            .atZoneSameInstant(ctx.zone)
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ctx.locale))
        return messageSource.getMessage("notification.slot_reminder", arrayOf(escapedTitle, localTime), ctx.locale)
    }

    private fun msg(key: String, locale: Locale): String = messageSource.getMessage(key, null, locale)

    private fun skip(notification: ScheduledNotificationEntity, reason: String) {
        finish(notification, NotificationStatus.SKIPPED)
        count("skipped", "none")
        log.debug("Skipped slot reminder {}: {}", notification.id, reason)
    }

    private fun finish(notification: ScheduledNotificationEntity, status: NotificationStatus) {
        notification.status = status
        repository.save(notification)
    }

    private fun count(outcome: String, content: String) {
        meterRegistry.counter(
            "tasker.notification.sent",
            "type", "slot_reminder",
            "channel", "telegram",
            "outcome", outcome,
            "content", content,
        ).increment()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
