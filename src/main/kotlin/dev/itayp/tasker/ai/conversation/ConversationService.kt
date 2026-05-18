package dev.itayp.tasker.ai.conversation

import dev.itayp.tasker.ai.ConversationConfig
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val clock: Clock,
) {
    @Transactional
    fun createConversation(userId: UUID, config: ConversationConfig): ConversationEntity {
        val now = clock.instant()
        return conversationRepository.save(ConversationEntity().apply {
            this.userId = userId
            this.conversationType = config.conversationType
            this.model = config.model
            this.temperature = config.temperature
            this.ttlDays = config.ttlDays
            this.status = ConversationStatus.ACTIVE
            this.createdAt = now
            this.lastActivityAt = now
            this.systemPrompt = config.systemPrompt
        })
    }

    @Transactional
    fun addMessage(
        conversationId: UUID,
        role: String,
        content: String?,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolName: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
    ): MessageEntity {
        val position = messageRepository.countByConversationId(conversationId).toInt()
        val message = messageRepository.save(MessageEntity().apply {
            this.conversationId = conversationId
            this.role = role
            this.content = content
            this.toolCallsJson = toolCallsJson
            this.toolCallId = toolCallId
            this.toolName = toolName
            this.position = position
            this.promptTokens = promptTokens
            this.completionTokens = completionTokens
            this.createdAt = clock.instant()
        })

        conversationRepository.findById(conversationId).ifPresent { conversation ->
            conversation.lastActivityAt = clock.instant()
            if (promptTokens != null) conversation.totalPromptTokens += promptTokens
            if (completionTokens != null) conversation.totalCompletionTokens += completionTokens
        }

        return message
    }

    @Transactional(readOnly = true)
    fun getMessages(conversationId: UUID): List<MessageEntity> =
        messageRepository.findByConversationIdOrderByPosition(conversationId)

    @Transactional(readOnly = true)
    fun findById(conversationId: UUID): ConversationEntity? =
        conversationRepository.findById(conversationId).orElse(null)

    @Transactional
    fun softDeleteExpired(now: Instant) {
        val toSoftDelete = conversationRepository.findAllByStatus(ConversationStatus.ACTIVE)
            .filter { it.lastActivityAt!!.plus(Duration.ofDays(it.ttlDays.toLong())).isBefore(now) }
        toSoftDelete.forEach {
            it.status = ConversationStatus.SOFT_DELETED
            it.softDeletedAt = now
        }
        conversationRepository.saveAll(toSoftDelete)
    }

    @Transactional
    fun hardDeleteOld(now: Instant, hardDeleteAfterDays: Long = 30) {
        val cutoff = now.minus(hardDeleteAfterDays, ChronoUnit.DAYS)
        val toDelete = conversationRepository.findAllByStatusAndSoftDeletedAtBefore(
            ConversationStatus.SOFT_DELETED, cutoff
        )
        conversationRepository.deleteAll(toDelete)
    }
}
