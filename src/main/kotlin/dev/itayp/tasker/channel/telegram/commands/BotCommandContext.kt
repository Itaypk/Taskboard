package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import java.util.UUID

data class BotCommandContext(
    val userId: UUID,
    val chatId: Long,
    val args: String,
    val channel: TelegramConversationChannel,
    val sessionRegistry: TelegramSessionRegistry,
    /**
     * The message the command was inferred from, when the user didn't type it — a message they sent
     * on their own (`docs/FREE-TEXT-CAPTURE.md` D3). Null for a typed command.
     *
     * Two things read it. Any handler that would otherwise leave the user no way out should offer
     * one ([inferred]), since the inference can be wrong; and a handler that opens a conversation
     * about what the user just said can carry the text into it rather than making them repeat
     * themselves. It is kept separate from [args] because the dispatcher overwrites those with
     * whatever followed the command word — nothing, on a route.
     */
    val inferredFrom: String? = null,
) {
    /** True when this command was inferred from a free-text message rather than typed. */
    val inferred: Boolean get() = inferredFrom != null
}
