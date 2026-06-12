package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.QuickAddState
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.QuickAddRegistry
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Instant
import java.util.Locale
import java.util.UUID

class AddBotCommandTest {

    private val flow: QuickAddFlow = mock()
    private val registry: QuickAddRegistry = mock()
    private val orchestrator: WeeklyPlanningOrchestrator = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
    }

    private val command = AddBotCommand(flow, registry, orchestrator, userSettingsService, messageSource)

    private val userId = UUID.randomUUID()
    private val chatId = 42L
    private val channel: TelegramConversationChannel = mock()
    private val sessionRegistry: TelegramSessionRegistry = mock()

    private fun context(args: String) = BotCommandContext(userId, chatId, args, channel, sessionRegistry)

    @Test
    fun `opens the flow and stores the resulting state`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        val state = QuickAddState.AwaitingDescription(Instant.now())
        whenever(flow.begin(userId, channel, "buy milk")).thenReturn(state)

        command.handle(context("buy milk"))

        verify(registry).set(chatId, state)
    }

    @Test
    fun `declines during an active planning session and redirects`() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        val sessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(sessionId)
        whenever(orchestrator.phase(sessionId)).thenReturn(WeeklyPlanningOrchestrator.Phase.CONVERSING)

        command.handle(context("buy milk"))

        verify(channel).send(any<ChannelMessage.Text>())
        verify(flow, never()).begin(any(), any(), any())
    }
}
