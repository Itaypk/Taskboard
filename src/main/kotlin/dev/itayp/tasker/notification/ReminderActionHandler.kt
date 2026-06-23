package dev.itayp.tasker.notification

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Handles a tap on a slot reminder's interactive menu (Phase 2b). The channel layer detects the
 * `rem:` callback-data prefix and delegates here; the action and target row are decoded straight from
 * the payload (see [ReminderAction]) and the row is reloaded from the DB — no in-memory registry.
 *
 * Channel-agnostic: confirmations are sent back through the same [ConversationChannel] the tap came
 * from. Replies carry the (decrypted) task title only to the user over their own channel — never logged.
 */
@Component
class ReminderActionHandler(
    private val repository: ScheduledNotificationRepository,
    private val slotReminderService: SlotReminderService,
    private val backlogTaskService: BacklogTaskService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ReminderActionHandler::class.java)

    /**
     * Returns true if [callbackData] was a reminder action (and has now been handled), false if it
     * isn't one of ours (so the caller can keep routing it through its other handlers).
     */
    fun handle(userId: UUID, channel: ConversationChannel, callbackData: String): Boolean {
        val parsed = ReminderAction.parse(callbackData) ?: return false
        val locale = userSettingsService.getLocale(userId)

        val notification = repository.findById(parsed.notificationId).orElse(null)
        // Guard against a stale button (row GC'd / account reset) or a payload not owned by this user.
        if (notification == null || notification.userId != userId) {
            channel.send(ChannelMessage.Text(msg("notification.action.expired", locale)))
            count(parsed.action, "stale")
            return true
        }

        when (parsed.action) {
            ReminderAction.ACK -> {
                channel.send(ChannelMessage.Text(msg("notification.ack.confirmed", locale)))
                count(parsed.action, "ok")
            }
            ReminderAction.SNOOZE_HOUR -> {
                slotReminderService.snooze(notification, Duration.ofHours(1))
                channel.send(ChannelMessage.Text(msg("notification.snooze.hour.confirmed", locale)))
                count(parsed.action, "ok")
            }
            ReminderAction.SNOOZE_DAY -> {
                slotReminderService.snooze(notification, Duration.ofDays(1))
                channel.send(ChannelMessage.Text(msg("notification.snooze.day.confirmed", locale)))
                count(parsed.action, "ok")
            }
            ReminderAction.MARK_DONE -> {
                val task = backlogTaskService.markDone(userId, notification.backlogTaskId!!)
                if (task == null) {
                    channel.send(ChannelMessage.Text(msg("notification.done.task_gone", locale)))
                    count(parsed.action, "task_gone")
                } else {
                    val title = channel.formatter.escape(task.title)
                    channel.send(ChannelMessage.Text(messageSource.getMessage("notification.done.confirmed", arrayOf(title), locale)))
                    count(parsed.action, "ok")
                }
            }
        }
        log.debug("Handled reminder action {} for notification {}", parsed.action, parsed.notificationId)
        return true
    }

    private fun msg(key: String, locale: java.util.Locale): String = messageSource.getMessage(key, null, locale)

    private fun count(action: ReminderAction, outcome: String) {
        meterRegistry.counter(
            "tasker.notification.action",
            "type", "slot_reminder",
            "channel", "telegram",
            "action", action.code,
            "outcome", outcome,
        ).increment()
    }
}
