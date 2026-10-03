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
import java.util.Locale
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
     * Entry point for a callback tap. Returns true if [callbackData] was a reminder action (and has
     * now been handled), false if it isn't one of ours (so the caller can keep routing it through its
     * other handlers). Parsing/validation and the action itself are split into [parseAndValidate] and
     * [process].
     */
    fun processReminderResponse(userId: UUID, channel: ConversationChannel, callbackData: String): Boolean =
        when (val parsed = parseAndValidate(userId, callbackData)) {
            ParsedReminder.NotAReminder -> false
            is ParsedReminder.Stale -> {
                channel.send(ChannelMessage.Text(msg("notification.action.expired", parsed.locale)))
                count(parsed.action, "stale", channel)
                true
            }
            is ParsedReminder.Ready -> {
                process(parsed, userId, channel)
                true
            }
        }

    /**
     * Decodes the callback data and resolves the target row. A payload that doesn't carry our prefix
     * is [ParsedReminder.NotAReminder] (silently not ours); a well-formed `rem:` payload that we still
     * can't parse is logged as an error (it indicates a bug) but treated the same way. A reminder whose
     * row is gone or owned by another user is [ParsedReminder.Stale].
     */
    private fun parseAndValidate(userId: UUID, callbackData: String): ParsedReminder {
        val parsed = ReminderAction.parse(callbackData)
        if (parsed == null) {
            if (callbackData.startsWith(ReminderAction.PREFIX)) {
                // Looks like one of ours but didn't decode — a malformed payload is a bug, not routing.
                log.error("Discarding unparseable reminder callback data: '{}'", callbackData)
            }
            return ParsedReminder.NotAReminder
        }
        val locale = userSettingsService.getLocale(userId)
        val notification = repository.findById(parsed.notificationId).orElse(null)
        // Guard against a stale button (row GC'd / account reset) or a payload not owned by this user.
        return if (notification == null || notification.userId != userId) {
            ParsedReminder.Stale(parsed.action, locale)
        } else {
            ParsedReminder.Ready(parsed.action, notification, locale)
        }
    }

    /** Carries out a validated reminder action and sends the user a localized confirmation. */
    private fun process(ready: ParsedReminder.Ready, userId: UUID, channel: ConversationChannel) {
        val (action, notification, locale) = ready
        when (action) {
            ReminderAction.ACK -> {
                channel.send(ChannelMessage.Text(msg("notification.ack.confirmed", locale)))
                count(action, "ok", channel)
            }
            ReminderAction.SNOOZE_HOUR -> {
                slotReminderService.snooze(notification, Duration.ofHours(1))
                channel.send(ChannelMessage.Text(msg("notification.snooze.hour.confirmed", locale)))
                count(action, "ok", channel)
            }
            ReminderAction.SNOOZE_DAY -> {
                slotReminderService.snooze(notification, Duration.ofDays(1))
                channel.send(ChannelMessage.Text(msg("notification.snooze.day.confirmed", locale)))
                count(action, "ok", channel)
            }
            ReminderAction.MARK_DONE -> {
                val task = backlogTaskService.markDone(userId, notification.backlogTaskId!!)
                if (task == null) {
                    channel.send(ChannelMessage.Text(msg("notification.done.task_gone", locale)))
                    count(action, "task_gone", channel)
                } else {
                    val title = channel.formatter.escape(task.title)
                    channel.send(ChannelMessage.Text(messageSource.getMessage("notification.done.confirmed", arrayOf(title), locale)))
                    count(action, "ok", channel)
                }
            }
        }
        log.debug("Handled reminder action {} for notification {}", action, notification.id)
    }

    private fun msg(key: String, locale: Locale): String = messageSource.getMessage(key, null, locale)

    private fun count(action: ReminderAction, outcome: String, channel: ConversationChannel) {
        meterRegistry.counter(
            "tasker.notification.action",
            "type", "slot_reminder",
            "channel", channel.type.metricTag,
            "action", action.code,
            "outcome", outcome,
        ).increment()
    }

    /** Outcome of decoding + validating a callback payload, consumed by [processReminderResponse]. */
    private sealed interface ParsedReminder {
        /** Not a reminder callback (or unparseable) — the caller should keep routing it. */
        data object NotAReminder : ParsedReminder

        /** A reminder whose row is gone or owned by someone else; the user gets an "expired" notice. */
        data class Stale(val action: ReminderAction, val locale: Locale) : ParsedReminder

        /** A valid, owned reminder ready to act on. */
        data class Ready(
            val action: ReminderAction,
            val notification: ScheduledNotificationEntity,
            val locale: Locale,
        ) : ParsedReminder
    }
}
