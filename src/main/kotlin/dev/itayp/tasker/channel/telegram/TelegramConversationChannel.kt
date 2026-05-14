package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelCapabilities
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient

class TelegramConversationChannel(
    private val chatId: Long,
    private val telegramClient: TelegramClient,
) : ConversationChannel {

    override val capabilities = ChannelCapabilities(
        supportsAutocompletions = true,
        supportsInlineButtons = true,
    )

    override fun indicateTyping() {
        runCatching {
            telegramClient.execute(
                SendChatAction.builder()
                    .chatId(chatId)
                    .action("typing")
                    .build()
            )
        }
    }

    override fun send(message: ChannelMessage) = when (message) {
        is ChannelMessage.Text -> sendText(message)
        is ChannelMessage.Choice -> sendChoice(message)
    }

    private fun sendText(message: ChannelMessage.Text) {
        val replyMarkup = if (message.completions.isNotEmpty()) {
            ReplyKeyboardMarkup.builder()
                .keyboard(message.completions.map { KeyboardRow(listOf(KeyboardButton(it))) })
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build()
        } else {
            ReplyKeyboardRemove.builder().removeKeyboard(true).build()
        }
        telegramClient.execute(
            SendMessage.builder()
                .chatId(chatId)
                .text(message.text)
                .parseMode("HTML")
                .replyMarkup(replyMarkup)
                .build()
        )
    }

    private fun sendChoice(message: ChannelMessage.Choice) {
        val rows = message.options.chunked(2).map { chunk ->
            InlineKeyboardRow(chunk.map { option ->
                InlineKeyboardButton.builder()
                    .text(option.label)
                    .callbackData(option.id)
                    .build()
            })
        }
        telegramClient.execute(
            SendMessage.builder()
                .chatId(chatId)
                .text(message.prompt)
                .parseMode("HTML")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build()
        )
    }
}
