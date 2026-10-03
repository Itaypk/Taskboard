package dev.itayp.tasker.notification.digest

import java.util.UUID

/**
 * The buttons under a daily digest. Like `notification.ReminderAction`, each button's callback data
 * is self-describing — `dig:<code>:<digestId>` — and the handler reloads the digest row from the DB,
 * so routing needs no in-memory registry and survives a restart.
 *
 * Callback data stays well within Telegram's 64-byte limit: `dig:` (4) + code (≤4) + `:` (1) +
 * a UUID (36) = ~45 bytes.
 */
enum class DailyDigestAction(val code: String) {
    /** Acknowledge — a thumbs-up, no state change. */
    ACK("ack"),

    /** Start the `/plan` flow: revise this week's plan, or start one. */
    REVISIT_PLAN("plan"),

    /** Mute the listed due tasks until the current plan week ends. */
    MUTE_DUE_UNTIL_NEXT_WEEK("mutw"),

    /** Mute the listed due tasks for a year — in effect, until their deadline changes. */
    MUTE_DUE_FOR_GOOD("muty");

    fun callbackData(digestId: UUID): String = "$PREFIX$code:$digestId"

    companion object {
        const val PREFIX = "dig:"

        private val byCode = entries.associateBy { it.code }

        /** Parses `dig:<code>:<uuid>` callback data, or null if it isn't a digest action. */
        fun parse(data: String): Parsed? {
            if (!data.startsWith(PREFIX)) return null
            val rest = data.removePrefix(PREFIX)
            val sep = rest.indexOf(':')
            if (sep <= 0) return null
            val action = byCode[rest.substring(0, sep)] ?: return null
            val digestId = runCatching { UUID.fromString(rest.substring(sep + 1)) }.getOrNull() ?: return null
            return Parsed(action, digestId)
        }
    }

    data class Parsed(val action: DailyDigestAction, val digestId: UUID)
}
