package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.repository.UserRepository
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.UUID

/**
 * Resolves the outbound conversation channel for a *scheduled* planning session — one that has
 * to reach the user unprompted. Today that means Telegram; a user without a Telegram identity
 * (email-only, or OAuth without a shared email) simply has no scheduled channel, and callers
 * must handle that rather than assuming one exists.
 *
 * This is the single place the "scheduled planning == Telegram" assumption lives, so additional
 * push channels (or an email-initiated planning link) can slot in later without touching the
 * scheduler. The web planning drawer is unaffected — it drives the orchestrator synchronously
 * and never needs a push channel.
 */
@Component
class ScheduledConversationChannelResolver(
    private val userRepository: UserRepository,
    private val telegramClient: TelegramClient?,
    private val sessionRegistry: TelegramSessionRegistry?,
) {

    /** A resolved push channel plus a hook to record the started session against that channel. */
    class Resolved(
        val channel: ConversationChannel,
        val onSessionStarted: (UUID) -> Unit,
    )

    /** True iff a scheduled planning conversation can actually be delivered to the user. */
    fun hasDeliverableChannel(userId: UUID): Boolean = telegramChatId(userId) != null

    /** The user's scheduled push channel, or null if they have none. */
    fun resolve(userId: UUID): Resolved? {
        val client = telegramClient ?: return null
        val chatId = telegramChatId(userId) ?: return null
        return Resolved(TelegramConversationChannel(chatId, client)) { sessionId ->
            sessionRegistry?.put(chatId, sessionId)
        }
    }

    private fun telegramChatId(userId: UUID): Long? {
        if (telegramClient == null) return null
        return userRepository.findById(userId).orElse(null)?.telegramId
    }
}
