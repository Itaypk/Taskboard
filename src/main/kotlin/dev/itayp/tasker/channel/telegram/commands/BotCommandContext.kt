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
     * True when the user didn't type this command — it was inferred from a message they sent on
     * their own (`docs/FREE-TEXT-CAPTURE.md` D3). Handlers that would otherwise leave the user no
     * way out should offer one, since the inference can be wrong.
     */
    val inferred: Boolean = false,
)
