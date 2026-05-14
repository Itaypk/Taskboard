package dev.itayp.tasker.channel.telegram.commands

interface BotCommandHandler {
    /** Command name without the leading slash, e.g. "plan". */
    val command: String

    /**
     * Short English description shown in Telegram's bot command menu (via setMyCommands) and
     * in /help output. Kept English-only for now — Telegram's command menu is registered once
     * per bot rather than per user.
     */
    val description: String

    fun handle(context: BotCommandContext)
}
