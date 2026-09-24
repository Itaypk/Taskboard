package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramReachabilityService
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
 *
 * A Telegram identity is necessary but not sufficient: the bot can only write to a chat the user
 * has opened, which [TelegramReachabilityService] tracks. Every resolved channel reports a refused
 * send back to it, so a chat that stops accepting messages drops out of scheduling by itself.
 */
@Component
class ScheduledConversationChannelResolver(
    private val userRepository: UserRepository,
    private val telegramClient: TelegramClient?,
    private val sessionRegistry: TelegramSessionRegistry?,
    private val reachability: TelegramReachabilityService,
) {

    /** A resolved push channel plus a hook to record the started session against that channel. */
    class Resolved(
        val channel: ConversationChannel,
        val onSessionStarted: (UUID) -> Unit,
    )

    /** True iff a scheduled planning conversation can actually be delivered to the user. */
    fun hasDeliverableChannel(userId: UUID): Boolean = telegramChatId(userId, requireReady = true) != null

    /** The user's scheduled push channel, or null if they have none. */
    fun resolve(userId: UUID): Resolved? = resolve(userId, requireReady = true)

    /**
     * Like [resolve], but also for a linked chat not yet known to be reachable — for the one send
     * whose job is to find out (the post-link welcome). Nothing recurring may use it: an unreachable
     * chat fails every attempt.
     */
    fun resolveUnconfirmed(userId: UUID): Resolved? = resolve(userId, requireReady = false)

    private fun resolve(userId: UUID, requireReady: Boolean): Resolved? {
        val client = telegramClient ?: return null
        val chatId = telegramChatId(userId, requireReady) ?: return null
        val channel = TelegramConversationChannel(chatId, client, onUnreachable = { reachability.markUnreachable(userId) })
        return Resolved(channel) { sessionId ->
            sessionRegistry?.put(chatId, sessionId)
        }
    }

    private fun telegramChatId(userId: UUID, requireReady: Boolean): Long? {
        if (telegramClient == null) return null
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (requireReady && user.telegramChatReadyAt == null) return null
        return user.telegramId
    }
}
