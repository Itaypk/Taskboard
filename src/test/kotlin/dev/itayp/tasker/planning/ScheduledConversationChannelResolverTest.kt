package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ScheduledConversationChannelResolverTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var telegramClient: TelegramClient
    @Mock lateinit var sessionRegistry: TelegramSessionRegistry

    private val userId = UUID.randomUUID()

    private fun resolver(
        client: TelegramClient? = telegramClient,
        registry: TelegramSessionRegistry? = sessionRegistry,
    ) = ScheduledConversationChannelResolver(userRepository, client, registry)

    @Test
    fun `no channel when telegram is not configured`() {
        val resolver = resolver(client = null)
        assertThat(resolver.hasDeliverableChannel(userId)).isFalse()
        assertThat(resolver.resolve(userId)).isNull()
    }

    @Test
    fun `no channel when the user has no telegram id`() {
        whenever(userRepository.findById(userId))
            .thenReturn(Optional.of(UserEntity().apply { id = userId; telegramId = null }))

        assertThat(resolver().hasDeliverableChannel(userId)).isFalse()
        assertThat(resolver().resolve(userId)).isNull()
    }

    @Test
    fun `resolves a telegram channel and records the started session against the chat`() {
        whenever(userRepository.findById(userId))
            .thenReturn(Optional.of(UserEntity().apply { id = userId; telegramId = 999L }))

        val resolved = resolver().resolve(userId)

        assertThat(resolved).isNotNull
        val sessionId = UUID.randomUUID()
        resolved!!.onSessionStarted(sessionId)
        verify(sessionRegistry).put(999L, sessionId)
    }
}
