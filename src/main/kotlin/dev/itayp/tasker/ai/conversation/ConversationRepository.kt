package dev.itayp.tasker.ai.conversation

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ConversationRepository : JpaRepository<ConversationEntity, UUID> {
    fun findAllByStatus(status: ConversationStatus): List<ConversationEntity>
    fun findAllByStatusAndSoftDeletedAtBefore(status: ConversationStatus, before: Instant): List<ConversationEntity>
}
