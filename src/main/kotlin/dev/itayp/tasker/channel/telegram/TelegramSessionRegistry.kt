package dev.itayp.tasker.channel.telegram

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class TelegramSessionRegistry {
    private val sessions = ConcurrentHashMap<Long, UUID>()

    fun get(chatId: Long): UUID? = sessions[chatId]
    fun put(chatId: Long, sessionId: UUID) { sessions[chatId] = sessionId }
    fun remove(chatId: Long) { sessions.remove(chatId) }
}
