package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelCapabilities
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.channel.MessageFormatter
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class TelegramConversationChannel(
    private val chatId: Long,
    private val telegramClient: TelegramClient,
    /** How often the chat action is re-sent while work is in flight. Overridden in tests. */
    private val typingRefresh: Duration = DEFAULT_TYPING_REFRESH,
) : ConversationChannel {

    override val capabilities = ChannelCapabilities(
        supportsAutocompletions = true,
        supportsInlineButtons = true,
    )

    override val formatter: MessageFormatter = HtmlMessageFormatter

    private fun indicateTyping() {
        runCatching {
            telegramClient.execute(
                SendChatAction.builder()
                    .chatId(chatId)
                    .action("typing")
                    .build()
            )
        }
    }

    /**
     * Telegram drops a chat action after ~5 seconds, so one [indicateTyping] goes stale well
     * before a planning turn returns, leaving the user staring at a silent chat. This re-issues
     * it on a virtual thread until [block] finishes — or until [MAX_TYPING_TICKS] is reached,
     * which caps a runaway at roughly three minutes.
     */
    override fun <T> whileWorking(block: () -> T): T {
        indicateTyping()
        val done = AtomicBoolean(false)
        val keepAlive = Thread.ofVirtual().start {
            var ticks = 0
            while (!done.get() && ticks++ < MAX_TYPING_TICKS) {
                try {
                    Thread.sleep(typingRefresh)
                } catch (_: InterruptedException) {
                    break
                }
                if (!done.get()) indicateTyping()
            }
        }
        return try {
            block()
        } finally {
            done.set(true)
            keepAlive.interrupt()
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

    companion object {
        /** Telegram clears a chat action after ~5 seconds, so refresh a beat inside that. */
        private val DEFAULT_TYPING_REFRESH: Duration = Duration.ofSeconds(4)

        /** Belt and braces against a block that never returns — roughly three minutes of typing. */
        private const val MAX_TYPING_TICKS = 45
    }
}
