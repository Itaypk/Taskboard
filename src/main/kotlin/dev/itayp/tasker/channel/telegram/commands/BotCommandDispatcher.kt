package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class BotCommandDispatcher(
    handlers: List<BotCommandHandler>,
    private val aiAccessService: AiAccessService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) {

    private val handlerMap: Map<String, BotCommandHandler> = handlers.associateBy { it.command }

    /**
     * Parses [text] as a bot command and routes to the matching handler.
     * Strips the leading slash and any @BotName suffix (used in group chats).
     * Returns true if a handler was found, false for unknown commands.
     *
     * AI-driven commands are pre-empted when the user has opted out, so the user gets a
     * clear message instead of the call failing inside the planner with a generic error.
     */
    fun dispatch(text: String, context: BotCommandContext): Boolean {
        val withoutSlash = text.removePrefix("/")
        val parts = withoutSlash.split(" ", limit = 2)
        val commandName = parts[0].substringBefore("@").lowercase()
        val args = if (parts.size > 1) parts[1].trim() else ""
        val handler = handlerMap[commandName] ?: return false
        // isAiAvailableForUser also rejects users whose only boards are restricted by an opted-out
        // co-member — the planner / quick-add would otherwise run with no usable boards.
        if (handler.requiresAi && !aiAccessService.isAiAvailableForUser(context.userId)) {
            val locale = userSettingsService.getLocale(context.userId)
            context.channel.send(ChannelMessage.Text(
                messageSource.getMessage("command.ai_disabled", null, locale)
            ))
            return true
        }
        handler.handle(context.copy(args = args))
        return true
    }
}
