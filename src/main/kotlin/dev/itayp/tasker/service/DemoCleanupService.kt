package dev.itayp.tasker.service

import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class DemoCleanupService(
    private val userRepository: UserRepository,
    private val taskRepository: BacklogTaskRepository,
    private val tagRepository: BacklogTaskTagRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val settingsRepository: UserSettingsRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    fun cleanupExpiredDemoUsers() {
        val expired = userRepository.findExpiredDemoUsers(Instant.now())
        if (expired.isEmpty()) return

        logger.info("Cleaning up ${expired.size} expired demo user(s)")

        for (user in expired) {
            val userId = user.id!!
            deleteSessionsForUser(userId)
            deleteTaskDataForUser(userId)
            settingsRepository.deleteById(userId)
        }
        userRepository.deleteAll(expired)

        logger.info("Demo cleanup complete")
    }

    private fun deleteSessionsForUser(userId: UUID) {
        // SPRING_SESSION_ATTRIBUTES cascades from SPRING_SESSION, so one delete suffices
        jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", userId.toString())
    }

    private fun deleteTaskDataForUser(userId: UUID) {
        val taskIds = taskRepository.findAllByUserId(userId).mapNotNull { it.id }
        if (taskIds.isNotEmpty()) {
            // backlog_task_tags has no ON DELETE CASCADE, so join rows must go first
            val placeholders = taskIds.joinToString(",") { "?" }
            jdbcTemplate.update(
                "DELETE FROM backlog_task_tags WHERE task_id IN ($placeholders)",
                *taskIds.map { it.toString() }.toTypedArray(),
            )
        }
        taskRepository.deleteAllByUserId(userId)
        tagRepository.deleteAllByUserId(userId)
        categoryRepository.deleteAllByUserId(userId)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DemoCleanupService::class.java)
    }
}
