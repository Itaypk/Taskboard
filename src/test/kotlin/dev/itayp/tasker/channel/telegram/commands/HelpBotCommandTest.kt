package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class HelpBotCommandTest {

    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var channel: TelegramConversationChannel
    @Mock private lateinit var sessionRegistry: TelegramSessionRegistry

    private val userId = UUID.randomUUID()
    private val chatId = 11L

    private val helpCommand by lazy {
        // Build the list with a self-reference: HelpBotCommand expects to enumerate all
        // handlers, including itself.
        val handlers = mutableListOf<BotCommandHandler>()
        val help = HelpBotCommand(
            handlers,
            userSettingsService,
            StaticMessageSource().also { src ->
                src.addMessage("command.help.header", Locale.ENGLISH, "Available commands")
            },
        )
        handlers += help
        handlers += stubHandler("plan", "Start or review your weekly planning session")
        handlers += stubHandler("current", "Show your current weekly plan")
        help
    }

    private fun context() = BotCommandContext(userId, chatId, "", channel, sessionRegistry)

    @BeforeEach
    fun setUp() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
    }

    @Test
    fun `lists all registered commands alphabetically`() {
        helpCommand.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val text = (captor.firstValue as ChannelMessage.Text).text

        val expected = """
            <b>Available commands</b>
            /current — Show your current weekly plan
            /help — List available commands
            /plan — Start or review your weekly planning session
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `escapes HTML in the header`() {
        val handlers = mutableListOf<BotCommandHandler>()
        val help = HelpBotCommand(
            handlers,
            userSettingsService,
            StaticMessageSource().also { src ->
                src.addMessage("command.help.header", Locale.ENGLISH, "<oops>")
            },
        )
        handlers += help

        help.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val text = (captor.firstValue as ChannelMessage.Text).text
        assertTrue(text.contains("<b>&lt;oops&gt;</b>"))
    }

    private fun stubHandler(name: String, desc: String): BotCommandHandler = object : BotCommandHandler {
        override val command = name
        override val description = desc
        override fun handle(context: BotCommandContext) = Unit
    }
}
