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
)
