package dev.itayp.tasker.ai.conversation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageRepository : JpaRepository<MessageEntity, UUID> {
    fun findByConversationIdOrderByPosition(conversationId: UUID): List<MessageEntity>
    fun countByConversationId(conversationId: UUID): Long
}
