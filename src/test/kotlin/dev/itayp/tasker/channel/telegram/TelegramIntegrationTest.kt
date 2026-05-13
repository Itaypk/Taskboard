package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.EnvTest
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates

/**
 * Manual integration tests that send and receive real messages through Telegram.
 *
 * Send tests skip automatically unless `.env.test` in the project root contains
 * all required keys (see `.env.test.example`).
 *
 * Receive tests are additionally marked `@Disabled` because they block waiting
 * for user interaction — enable one at a time, run it, and send/tap the expected
 * input in your Telegram client within the polling window.
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

    // -------------------------------------------------------------------------
    // Receive tests — @Disabled because they block for user interaction.
    // Enable one at a time, run it, then send/tap the expected input in Telegram
    // within the polling window shown in the test name.
    // -------------------------------------------------------------------------

    @Disabled("Requires sending a text message to the bot within the 30-second polling window")
    @Test
    fun `receives a text message sent to the bot (30s window)`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val client = OkHttpTelegramClient(env.getValue("TASKER_TELEGRAM_BOT_TOKEN"))

        val updates = client.execute(
            GetUpdates().apply {
                timeout = 30
                limit = 5
                allowedUpdates = listOf("message")
            },
        )

        assumeTrue(updates.isNotEmpty(), "No updates received — send a message to the bot and retry")

        val update = updates.first { it.hasMessage() && it.message.hasText() }
        val message = update.message
        assertNotNull(message.text, "message.text should not be null")
        assertNotNull(message.from, "message.from should not be null")
        assertTrue(message.chatId != 0L, "chatId should be non-zero")
        println("Received text '${message.text}' from userId=${message.from.id} in chatId=${message.chatId}")
    }

    @Disabled("Requires tapping an inline button within the 60-second polling window after the choice message appears")
    @Test
    fun `receives a callback query after sending an inline keyboard (60s window)`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val client = OkHttpTelegramClient(env.getValue("TASKER_TELEGRAM_BOT_TOKEN"))
        val channel = buildChannel(env)

        val options = listOf(
            ChoiceOption(id = "opt_yes", label = "Yes"),
            ChoiceOption(id = "opt_no", label = "No"),
            ChoiceOption(id = "opt_maybe", label = "Maybe"),
        )
        channel.send(
            ChannelMessage.Choice(
                prompt = "<b>[Integration Test]</b> Tap one of the buttons below within 60 seconds:",
                options = options,
            ),
        )

        val updates = client.execute(
            GetUpdates().apply {
                timeout = 60
                limit = 5
                allowedUpdates = listOf("callback_query")
            },
        )

        assumeTrue(updates.isNotEmpty(), "No callback received — tap a button in the chat and retry")

        val update = updates.first { it.hasCallbackQuery() }
        val query = update.callbackQuery
        val expectedIds = options.map { it.id }.toSet()
        assertTrue(query.data in expectedIds, "callbackData '${query.data}' was not one of $expectedIds")
        assertNotNull(query.from, "callbackQuery.from should not be null")
        assertEquals(env.getValue("TEST_TELEGRAM_CHAT_ID").toLong(), query.message.chatId)
        println("Received callback '${query.data}' from userId=${query.from.id}")
    }
}
