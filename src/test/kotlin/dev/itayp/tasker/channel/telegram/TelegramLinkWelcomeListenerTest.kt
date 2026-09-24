package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.ChannelUnreachableException
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.service.TelegramLinkedEvent
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import java.util.Locale
import java.util.UUID

/** The post-link welcome is also the probe that tells a freshly-linked chat is reachable. */
class TelegramLinkWelcomeListenerTest {

    private val userId = UUID.randomUUID()
    private val channelResolver: ScheduledConversationChannelResolver = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val reachability: TelegramReachabilityService = mock()
    private val channel: ConversationChannel = mock()

    private val messageSource = StaticMessageSource().apply {
        addMessage("command.link.welcome", Locale.ENGLISH, "Telegram is connected")
    }

    private val listener = TelegramLinkWelcomeListener(channelResolver, reachability, userSettingsService, messageSource)

    init {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(channelResolver.resolveUnconfirmed(userId))
            .thenReturn(ScheduledConversationChannelResolver.Resolved(channel) {})
    }

    @Test
    fun `a delivered welcome marks the chat reachable`() {
        listener.onTelegramLinked(TelegramLinkedEvent(userId))

        verify(channel).send(any())
        verify(reachability).markReachable(userId)
    }

    @Test
    fun `a chat the user has not opened yet is left unconfirmed`() {
        doThrow(ChannelUnreachableException("chat not found", RuntimeException())).whenever(channel).send(any())

        listener.onTelegramLinked(TelegramLinkedEvent(userId))

        verify(reachability, never()).markReachable(any())
    }

    @Test
    fun `any other failure is swallowed and proves nothing`() {
        doThrow(IllegalStateException("boom")).whenever(channel).send(any())

        listener.onTelegramLinked(TelegramLinkedEvent(userId))

        verify(reachability, never()).markReachable(any())
    }
}
