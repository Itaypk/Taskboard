package dev.itayp.tasker.ai.conversation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ConversationRepository : JpaRepository<ConversationEntity, UUID> {
    fun findAllByStatus(status: ConversationStatus): List<ConversationEntity>
    fun countByStatus(status: ConversationStatus): Long
    fun findAllByStatusAndSoftDeletedAtBefore(status: ConversationStatus, before: Instant): List<ConversationEntity>
}
