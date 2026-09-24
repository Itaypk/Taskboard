package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChannelUnreachableException
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.service.TelegramLinkedEvent
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Greets a user on Telegram the first time they connect it (see [TelegramLinkedEvent]). Runs after
 * the link transaction commits, so we never message someone whose link was rolled back. The message
 * is localized to the (already-established) user's language preference — unlike the always-English
 * /start greeting, this recipient has settings.
 *
 * It doubles as the reachability probe. A fresh link isn't known to be reachable yet — the user may
 * never have opened the chat with the bot — so this is the one push allowed to try anyway: success
 * marks the chat ready (which registers the planning cron), and "chat not found" is the expected
 * outcome for someone who hasn't pressed Start yet. Settings then walks them into the chat.
 *
 * Best-effort: a channel that can't be resolved (Telegram disabled) or a send failure is logged and
 * swallowed — a missed welcome must not surface as an error in the linking flow.
 */
@Component
class TelegramLinkWelcomeListener(
    private val channelResolver: ScheduledConversationChannelResolver,
    private val reachability: TelegramReachabilityService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onTelegramLinked(event: TelegramLinkedEvent) {
        val resolved = channelResolver.resolveUnconfirmed(event.userId) ?: return
        try {
            val locale = userSettingsService.getLocale(event.userId)
            val text = messageSource.getMessage("command.link.welcome", null, locale)
            resolved.channel.send(ChannelMessage.Text(text))
            reachability.markReachable(event.userId)
        } catch (e: ChannelUnreachableException) {
            logger.info("User {} linked Telegram but hasn't opened the chat with the bot yet", event.userId)
        } catch (e: Exception) {
            logger.warn("Failed to send Telegram link welcome to user {}", event.userId, e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramLinkWelcomeListener::class.java)
    }
}
