package dev.itayp.tasker.ai.conversation

import dev.itayp.tasker.ai.ConversationConfig
import dev.itayp.tasker.crypto.UserCryptoService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Plaintext view of a stored message. Returned to callers so they never have to
 * touch ciphertext on [MessageEntity] directly.
 */
data class StoredMessage(
    val id: UUID,
    val conversationId: UUID,
    val role: String,
    val content: String?,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val position: Int,
)

/** Plaintext view of a conversation, with [systemPrompt] decrypted. */
data class StoredConversation(
    val id: UUID,
    val userId: UUID,
    val conversationType: String,
    val model: String,
    val temperature: Double?,
    val status: ConversationStatus,
    val systemPrompt: String?,
)

@Service
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val userCrypto: UserCryptoService,
    private val clock: Clock,
) {
    @Transactional
    fun createConversation(userId: UUID, config: ConversationConfig): StoredConversation {
        val now = clock.instant()
        val saved = conversationRepository.save(ConversationEntity().apply {
            this.userId = userId
            this.conversationType = config.conversationType
            this.model = config.model
            this.temperature = config.temperature
            this.ttlDays = config.ttlDays
            this.status = ConversationStatus.ACTIVE
            this.createdAt = now
            this.lastActivityAt = now
            this.systemPrompt = userCrypto.encrypt(userId, config.systemPrompt)
        })
        return saved.toStoredConversation(config.systemPrompt)
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
    ): StoredMessage {
        val conversation = conversationRepository.findById(conversationId).orElseThrow {
            NoSuchElementException("Conversation $conversationId not found")
        }
        val ownerId = conversation.userId
            ?: error("Conversation $conversationId is missing userId")
        val position = messageRepository.countByConversationId(conversationId).toInt()
        val now = clock.instant()
        val saved = messageRepository.save(MessageEntity().apply {
            this.conversationId = conversationId
            this.role = role
            this.content = userCrypto.encrypt(ownerId, content)
            this.toolCallsJson = userCrypto.encrypt(ownerId, toolCallsJson)
            this.toolCallId = toolCallId
            this.toolName = toolName
            this.position = position
            this.promptTokens = promptTokens
            this.completionTokens = completionTokens
            this.createdAt = now
        })

        conversation.lastActivityAt = now
        if (promptTokens != null) conversation.totalPromptTokens += promptTokens
        if (completionTokens != null) conversation.totalCompletionTokens += completionTokens

        return StoredMessage(
            id = saved.id!!,
            conversationId = conversationId,
            role = role,
            content = content,
            toolCallsJson = toolCallsJson,
            toolCallId = toolCallId,
            toolName = toolName,
            position = position,
        )
    }

    @Transactional(readOnly = true)
    fun getMessages(conversationId: UUID): List<StoredMessage> {
        val ownerId = conversationRepository.findById(conversationId).orElseThrow {
            NoSuchElementException("Conversation $conversationId not found")
        }.userId ?: error("Conversation $conversationId is missing userId")
        return messageRepository.findByConversationIdOrderByPosition(conversationId).map { entity ->
            StoredMessage(
                id = entity.id!!,
                conversationId = conversationId,
                role = entity.role!!,
                content = userCrypto.decrypt(ownerId, entity.content),
                toolCallsJson = userCrypto.decrypt(ownerId, entity.toolCallsJson),
                toolCallId = entity.toolCallId,
                toolName = entity.toolName,
                position = entity.position,
            )
        }
    }

    @Transactional(readOnly = true)
    fun findById(conversationId: UUID): StoredConversation? {
        val entity = conversationRepository.findById(conversationId).orElse(null) ?: return null
        return entity.toStoredConversation()
    }

    private fun ConversationEntity.toStoredConversation(plaintextSystemPrompt: String? = null): StoredConversation {
        val ownerId = userId ?: error("Conversation $id is missing userId")
        return StoredConversation(
            id = id!!,
            userId = ownerId,
            conversationType = conversationType!!,
            model = model!!,
            temperature = temperature,
            status = status,
            systemPrompt = plaintextSystemPrompt ?: userCrypto.decrypt(ownerId, systemPrompt),
        )
    }

    @Transactional
    fun softDeleteExpired(now: Instant): Int {
        val toSoftDelete = conversationRepository.findAllByStatus(ConversationStatus.ACTIVE)
            .filter { it.lastActivityAt!!.plus(Duration.ofDays(it.ttlDays.toLong())).isBefore(now) }
        toSoftDelete.forEach {
            it.status = ConversationStatus.SOFT_DELETED
            it.softDeletedAt = now
        }
        conversationRepository.saveAll(toSoftDelete)
        return toSoftDelete.size
    }

    @Transactional
    fun hardDeleteOld(now: Instant, hardDeleteAfterDays: Long = 30): Int {
        val cutoff = now.minus(hardDeleteAfterDays, ChronoUnit.DAYS)
        val toDelete = conversationRepository.findAllByStatusAndSoftDeletedAtBefore(
            ConversationStatus.SOFT_DELETED, cutoff
        )
        conversationRepository.deleteAll(toDelete)
        return toDelete.size
    }
}
