package dev.itayp.tasker.channel.telegram.commands

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class BotCommandDispatcher(handlers: List<BotCommandHandler>) {

    private val handlerMap: Map<String, BotCommandHandler> = handlers.associateBy { it.command }

    /**
     * Parses [text] as a bot command and routes to the matching handler.
     * Strips the leading slash and any @BotName suffix (used in group chats).
     * Returns true if a handler was found, false for unknown commands.
     */
    fun dispatch(text: String, context: BotCommandContext): Boolean {
        val withoutSlash = text.removePrefix("/")
        val parts = withoutSlash.split(" ", limit = 2)
        val commandName = parts[0].substringBefore("@").lowercase()
        val args = if (parts.size > 1) parts[1].trim() else ""
        val handler = handlerMap[commandName] ?: return false
        handler.handle(context.copy(args = args))
        return true
    }
}
