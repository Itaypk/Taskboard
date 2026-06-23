package dev.itayp.tasker.notification

import java.util.UUID

/**
 * The interactive menu attached to a slot reminder. Each option's button carries self-describing
 * callback data — `rem:<code>:<notificationId>` — so a tap is routed and handled straight from the
 * payload, with the row reloaded from the DB. No in-memory registry to manage, and it survives a
 * restart (consistent with the rest of the notification feature, whose state lives in the table).
 *
 * Callback data must stay within Telegram's 64-byte limit: `rem:` (4) + code (≤5) + `:` (1) +
 * a UUID (36) = ~46 bytes.
 */
enum class ReminderAction(val code: String) {
    /** Acknowledge / dismiss — a thumbs-up, no state change. */
    ACK("ack"),
    SNOOZE_HOUR("snzh"),
    SNOOZE_DAY("snzd"),
    MARK_DONE("done");

    fun callbackData(notificationId: UUID): String = "$PREFIX$code:$notificationId"

    companion object {
        const val PREFIX = "rem:"

        private val byCode = entries.associateBy { it.code }

        /** Parses `rem:<code>:<uuid>` callback data, or null if it isn't a reminder action. */
        fun parse(data: String): Parsed? {
            if (!data.startsWith(PREFIX)) return null
            val rest = data.removePrefix(PREFIX)
            val sep = rest.indexOf(':')
            if (sep <= 0) return null
            val action = byCode[rest.substring(0, sep)] ?: return null
            val notificationId = runCatching { UUID.fromString(rest.substring(sep + 1)) }.getOrNull() ?: return null
            return Parsed(action, notificationId)
        }
    }

    data class Parsed(val action: ReminderAction, val notificationId: UUID)
}
