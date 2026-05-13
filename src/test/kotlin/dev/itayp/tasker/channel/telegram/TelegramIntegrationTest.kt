package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.EnvTest
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient

/**
 * Manual integration tests that send real messages through Telegram.
 *
 * They are skipped automatically unless `.env.test` in the project root
 * contains all required keys (see `.env.test.example`).  Because Telegram
 * is interactive, you will see the messages arrive in the target chat as
 * each test runs.
 *
 * Run all tests in this class:
 *   ./gradlew test --tests "dev.itayp.tasker.channel.telegram.TelegramIntegrationTest.*"
 *
 * Required keys in `.env.test`:
 *   TASKER_TELEGRAM_BOT_TOKEN — bot token from @BotFather
 *   TEST_TELEGRAM_CHAT_ID     — target chat ID (get it via /getUpdates after messaging the bot)
 */
class TelegramIntegrationTest {

    private companion object {
        val REQUIRED_KEYS = arrayOf(
            "TASKER_TELEGRAM_BOT_TOKEN",
            "TEST_TELEGRAM_CHAT_ID",
        )
    }

    /** Builds a live [TelegramConversationChannel] from `.env.test`, or skips. */
    private fun buildChannel(env: Map<String, String>): TelegramConversationChannel {
        val client = OkHttpTelegramClient(env.getValue("TASKER_TELEGRAM_BOT_TOKEN"))
        val chatId = env.getValue("TEST_TELEGRAM_CHAT_ID").toLong()
        return TelegramConversationChannel(chatId, client)
    }

    @Test
    fun `sends a plain text message`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val channel = buildChannel(env)

        channel.send(
            ChannelMessage.Text(
                text = "<b>[Integration Test]</b> Plain text message from <code>TelegramIntegrationTest</code>.\n\nIf you see this, basic message delivery works.",
            ),
        )
    }

    @Test
    fun `sends a text message with autocomplete suggestions (reply keyboard)`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val channel = buildChannel(env)

        channel.send(
            ChannelMessage.Text(
                text = "<b>[Integration Test]</b> Text message with reply-keyboard suggestions.\n\nYou should see suggestion buttons below the input field.",
                completions = listOf("Option A", "Option B", "Option C"),
            ),
        )
    }

    @Test
    fun `sends a choice message with inline buttons`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val channel = buildChannel(env)

        channel.send(
            ChannelMessage.Choice(
                prompt = "<b>[Integration Test]</b> Choice message — pick one of the options below:",
                options = listOf(
                    ChoiceOption(id = "opt_yes", label = "Yes"),
                    ChoiceOption(id = "opt_no", label = "No"),
                    ChoiceOption(id = "opt_maybe", label = "Maybe"),
                    ChoiceOption(id = "opt_later", label = "Later"),
                ),
            ),
        )
    }
}
