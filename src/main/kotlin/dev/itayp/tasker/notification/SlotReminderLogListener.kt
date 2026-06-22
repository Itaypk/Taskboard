package dev.itayp.tasker.notification

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Phase-1 placeholder handler: logs that a reminder came due (UUIDs only). This is the seam where a
 * later phase plugs in actual delivery — resolving the user's channel, generating localized / AI copy,
 * and pushing it (e.g. over Telegram). Keep it the single subscriber so the trigger/handler split
 * stays clean.
 */
@Component
class SlotReminderLogListener {
    private val log = LoggerFactory.getLogger(SlotReminderLogListener::class.java)

    @EventListener
    fun on(event: SlotReminderDueEvent) {
        log.info(
            "Slot reminder due (no-op handler): notification={} user={} session={} task={}",
            event.notificationId, event.userId, event.sessionId, event.backlogTaskId,
        )
    }
}
