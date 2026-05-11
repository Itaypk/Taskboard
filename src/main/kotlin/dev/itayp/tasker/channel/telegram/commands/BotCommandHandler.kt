package dev.itayp.tasker.channel.telegram.commands

interface BotCommandHandler {
    /** Command name without the leading slash, e.g. "plan". */
    val command: String
    fun handle(context: BotCommandContext)
}
