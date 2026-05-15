package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils

/**
 * Lists the bot's available commands. Iterates the live [BotCommandHandler] beans
 * (lazily, to break the constructor cycle of "handler depends on list of handlers")
 * so the help text stays in sync as new commands are added.
 */
@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class HelpBotCommand(
    @Lazy private val allCommands: List<BotCommandHandler>,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) : BotCommandHandler {

    override val command = "help"

    override val description = "List available commands"

    override fun handle(context: BotCommandContext) {
        val locale = userSettingsService.getLocale(context.userId)
        val header = messageSource.getMessage("command.help.header", null, locale)
        val lines = allCommands
            .sortedBy { it.command }
            .joinToString(separator = "\n") { handler ->
                "/${handler.command} — ${handler.description}"
            }
        context.channel.send(ChannelMessage.Text("<b>${HtmlUtils.htmlEscape(header)}</b>\n$lines"))
    }

}
