package dev.itayp.tasker.ai

import dev.itayp.tasker.ai.conversation.ConversationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ConversationCleanupTask(
    private val conversationService: ConversationService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ConversationCleanupTask::class.java)

    @Scheduled(cron = "0 0 3 * * *")
    fun cleanup() {
        val now = clock.instant()
        log.debug("Running AI conversation cleanup at {}", now)
        val softDeletedCount = conversationService.softDeleteExpired(now)
        val hardDeletedCount = conversationService.hardDeleteOld(now)
        log.info("Finished AI conversation cleanup started at {}, soft-deleted {} conversations, deleted {} conversations", now, softDeletedCount, hardDeletedCount)
    }
}
