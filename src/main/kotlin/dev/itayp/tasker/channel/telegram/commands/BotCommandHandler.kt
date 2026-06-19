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

    /**
     * True when this command's flow drives the LLM (planner, quick-add). Commands that read
     * existing data without invoking the model leave this false. Used by the dispatcher to
     * pre-empt AI-disabled users (clean message instead of a buried `AiDisabledException`)
     * and by /help to hide commands that wouldn't do anything for them.
     */
    val requiresAi: Boolean
        get() = false

    fun handle(context: BotCommandContext)
}
