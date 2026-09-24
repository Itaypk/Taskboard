package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChannelUnreachableException
import dev.itayp.tasker.channel.telegram.TelegramReachabilityService
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.Serializable
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ScheduledConversationChannelResolverTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var telegramClient: TelegramClient
    @Mock lateinit var sessionRegistry: TelegramSessionRegistry
    @Mock lateinit var reachability: TelegramReachabilityService

    private val userId = UUID.randomUUID()

    private fun resolver(
        client: TelegramClient? = telegramClient,
        registry: TelegramSessionRegistry? = sessionRegistry,
    ) = ScheduledConversationChannelResolver(userRepository, client, registry, reachability)

    private fun stubUser(telegramId: Long?, chatReadyAt: Instant?) {
        whenever(userRepository.findById(userId)).thenReturn(
            Optional.of(UserEntity().apply {
                id = userId
                this.telegramId = telegramId
                telegramChatReadyAt = chatReadyAt
            }),
        )
    }

    @Test
    fun `no channel when telegram is not configured`() {
        val resolver = resolver(client = null)
        assertThat(resolver.hasDeliverableChannel(userId)).isFalse()
        assertThat(resolver.resolve(userId)).isNull()
    }

    @Test
    fun `no channel when the user has no telegram id`() {
        stubUser(telegramId = null, chatReadyAt = null)

        assertThat(resolver().hasDeliverableChannel(userId)).isFalse()
        assertThat(resolver().resolve(userId)).isNull()
    }

    /**
     * The production failure this guards: a user linked Telegram on the web, never opened the chat,
     * and got a weekly cron that could only ever fail with "chat not found".
     */
    @Test
    fun `a linked chat the user never opened is not deliverable`() {
        stubUser(telegramId = 999L, chatReadyAt = null)

        assertThat(resolver().hasDeliverableChannel(userId)).isFalse()
        assertThat(resolver().resolve(userId)).isNull()
    }

    @Test
    fun `the post-link probe may still try a chat not yet known to be reachable`() {
        stubUser(telegramId = 999L, chatReadyAt = null)

        assertThat(resolver().resolveUnconfirmed(userId)).isNotNull
    }

    @Test
    fun `resolves a telegram channel and records the started session against the chat`() {
        stubUser(telegramId = 999L, chatReadyAt = Instant.parse("2026-09-01T00:00:00Z"))

        assertThat(resolver().hasDeliverableChannel(userId)).isTrue()
        val resolved = resolver().resolve(userId)

        assertThat(resolved).isNotNull
        val sessionId = UUID.randomUUID()
        resolved!!.onSessionStarted(sessionId)
        verify(sessionRegistry).put(999L, sessionId)
    }

    @Test
    fun `a refused send marks the user unreachable`() {
        stubUser(telegramId = 999L, chatReadyAt = Instant.parse("2026-09-01T00:00:00Z"))
        val response = ApiResponse.builder<Serializable>()
            .ok(false).errorCode(403).errorDescription("Forbidden: bot was blocked by the user").build()
        whenever(telegramClient.execute(any<BotApiMethod<Serializable>>()))
            .thenThrow(TelegramApiRequestException("Error sending message", response))

        val channel = resolver().resolve(userId)!!.channel

        assertThatThrownBy { channel.send(ChannelMessage.Text("hi")) }
            .isInstanceOf(ChannelUnreachableException::class.java)
        verify(reachability).markUnreachable(userId)
    }
}
