package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChannelUnreachableException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the activity indicator — Telegram clears a chat action after ~5 seconds, so the point of
 * `whileWorking` is that it keeps re-sending one for as long as the model takes — and how a refused
 * send is classified.
 */
class TelegramConversationChannelTest {

    private val chatActions = AtomicInteger()
    private val telegramClient: TelegramClient = mock()

    private val channel = TelegramConversationChannel(
        chatId = 42L,
        telegramClient = telegramClient,
        typingRefresh = Duration.ofMillis(30),
    )

    init {
        whenever(telegramClient.execute(any<BotApiMethod<Serializable>>())).thenAnswer { invocation ->
            if (invocation.arguments[0] is SendChatAction) chatActions.incrementAndGet()
            null
        }
    }

    @Test
    fun `the typing indicator is re-sent for as long as the work takes`() {
        val result = channel.whileWorking {
            Thread.sleep(300)
            "done"
        }

        assertEquals("done", result)
        // One up front plus a refresh every 30ms — the exact count is timing-dependent, the point
        // is that it did not stop at the first one.
        assertTrue(chatActions.get() >= 3, "expected repeated chat actions, got ${chatActions.get()}")
    }

    @Test
    fun `the indicator stops once the work is done`() {
        channel.whileWorking { Thread.sleep(100) }
        // A tick already in flight when the block returned is allowed to land before we count.
        Thread.sleep(100)
        val settled = chatActions.get()

        Thread.sleep(200)

        assertEquals(settled, chatActions.get(), "the keep-alive outlived the work it was for")
    }

    @Test
    fun `a failing block stops the indicator and propagates`() {
        assertFailsWith<IllegalStateException> {
            channel.whileWorking { throw IllegalStateException("model exploded") }
        }
        Thread.sleep(100)
        val settled = chatActions.get()

        Thread.sleep(200)

        assertEquals(settled, chatActions.get(), "the keep-alive outlived a failed turn")
    }

    private fun telegramError(code: Int, description: String) = TelegramApiRequestException(
        "Error sending message",
        ApiResponse.builder<Serializable>().ok(false).errorCode(code).errorDescription(description).build(),
    )

    @Test
    fun `a chat the user never opened or has blocked counts as unreachable`() {
        assertTrue(TelegramConversationChannel.isRecipientUnreachable(telegramError(400, "Bad Request: chat not found")))
        assertTrue(TelegramConversationChannel.isRecipientUnreachable(telegramError(403, "Forbidden: bot was blocked by the user")))
        assertTrue(TelegramConversationChannel.isRecipientUnreachable(telegramError(403, "Forbidden: user is deactivated")))
    }

    /** Our own malformed message must not stop every future push to a perfectly good chat. */
    @Test
    fun `other bad requests are not a reachability problem`() {
        assertFalse(TelegramConversationChannel.isRecipientUnreachable(telegramError(400, "Bad Request: can't parse entities")))
        assertFalse(TelegramConversationChannel.isRecipientUnreachable(telegramError(429, "Too Many Requests: retry after 5")))
    }

    @Test
    fun `an unreachable chat is reported once and surfaces as a channel-level failure`() {
        var reported = 0
        val client: TelegramClient = mock()
        whenever(client.execute(any<BotApiMethod<Serializable>>()))
            .thenThrow(telegramError(400, "Bad Request: chat not found"))
        val push = TelegramConversationChannel(chatId = 42L, telegramClient = client, onUnreachable = { reported++ })

        assertFailsWith<ChannelUnreachableException> { push.send(ChannelMessage.Text("hi")) }
        assertEquals(1, reported)
    }

    @Test
    fun `any other send failure propagates untouched`() {
        var reported = 0
        val client: TelegramClient = mock()
        whenever(client.execute(any<BotApiMethod<Serializable>>()))
            .thenThrow(telegramError(400, "Bad Request: can't parse entities"))
        val push = TelegramConversationChannel(chatId = 42L, telegramClient = client, onUnreachable = { reported++ })

        assertFailsWith<TelegramApiRequestException> { push.send(ChannelMessage.Text("hi")) }
        assertEquals(0, reported)
    }
}
