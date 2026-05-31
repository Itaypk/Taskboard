package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.model.response.CategoryExport
import dev.itayp.tasker.model.response.SettingsExport
import dev.itayp.tasker.model.response.TagExport
import dev.itayp.tasker.model.response.TaskExport
import dev.itayp.tasker.model.response.UserExport
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AccountService(
    private val userRepository: UserRepository,
    private val taskRepository: BacklogTaskRepository,
    private val tagRepository: BacklogTaskTagRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val settingsRepository: UserSettingsRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val userCrypto: UserCryptoService,
) {

    /** Deletes all data for a user then the user row itself. */
    @Transactional
    fun deleteAccount(userId: UUID) {
        deleteUserData(userId)
        userRepository.deleteById(userId)
        logger.info("Deleted account for user {}", userId)
    }

    /**
     * Deletes all data owned by a user — sessions, tasks, tags, categories, settings —
     * but does NOT delete the user row. Used by both account deletion and demo cleanup
     * (which batch-deletes user rows separately for efficiency).
     */
    @Transactional
    fun deleteUserData(userId: UUID) {
        // Sessions: SPRING_SESSION_ATTRIBUTES cascades from SPRING_SESSION
        jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", userId.toString())

        // Task join table has no ON DELETE CASCADE, so join rows must go first
        val taskIds = taskRepository.findAllByUserIdOrderBySortKeyAsc(userId).mapNotNull { it.id }
        if (taskIds.isNotEmpty()) {
            val placeholders = taskIds.joinToString(",") { "?" }
            jdbcTemplate.update(
                "DELETE FROM backlog_task_tags WHERE task_id IN ($placeholders)",
                *taskIds.toTypedArray(),
            )
        }
        taskRepository.deleteAllByUserId(userId)
        tagRepository.deleteAllByUserId(userId)
        categoryRepository.deleteAllByUserId(userId)
        settingsRepository.deleteById(userId)

        // backlog_task_change_event has FKs to both users and planning_session, so it goes first
        jdbcTemplate.update("DELETE FROM backlog_task_change_event WHERE user_id = ?", userId)
        // planned_task_slot → planned_task → planning_session; no user_id on slot, so use a subquery
        jdbcTemplate.update(
            "DELETE FROM planned_task_slot WHERE planned_task_id IN (SELECT id FROM planned_task WHERE user_id = ?)",
            userId,
        )
        jdbcTemplate.update("DELETE FROM planned_task WHERE user_id = ?", userId)
        // planning_session has FKs to both users and ai_conversation
        jdbcTemplate.update("DELETE FROM planning_session WHERE user_id = ?", userId)
        // ai_message cascades automatically from ai_conversation (ON DELETE CASCADE in schema)
        jdbcTemplate.update("DELETE FROM ai_conversation WHERE user_id = ?", userId)
        // user_data_key has FK to users; remove last so the user row delete can proceed.
        jdbcTemplate.update("DELETE FROM user_data_key WHERE user_id = ?", userId)
    }

    /**
     * True iff the account holds nothing beyond what registration auto-seeds. Used by
     * the import flow to reject a 409 attempt to import on top of existing user data:
     * import wipes the seeded categories and overwrites settings, so anything else is
     * work the user did and we must not clobber it.
     */
    @Transactional(readOnly = true)
    fun isEmptyForImport(userId: UUID): Boolean {
        if (taskRepository.findAllByUserIdOrderBySortKeyAsc(userId).isNotEmpty()) return false
        if (tagRepository.findAllByUserId(userId).isNotEmpty()) return false
        val expected = UserService.DEFAULT_CATEGORIES.toSet()
        val actual = categoryRepository.findAllByUserId(userId).mapNotNull { entity ->
            val label = entity.label ?: return@mapNotNull null
            val swatch = entity.swatchId ?: return@mapNotNull null
            label to swatch
        }.toSet()
        return actual == expected
    }

    @Transactional(readOnly = true)
    fun exportAccount(userId: UUID): AccountExportResponse {
        val user = userRepository.findById(userId).orElseThrow()
        val settings = settingsRepository.findById(userId).orElse(null)
        val categories = categoryRepository.findAllByUserId(userId)
        val tags = tagRepository.findAllByUserId(userId)
        val tasks = taskRepository.findAllByUserIdOrderBySortKeyAsc(userId)

        return AccountExportResponse(
            formatVersion = 1,
            exportedAt = Instant.now().toString(),
            user = UserExport(
                id = user.id.toString(),
                telegramUsername = user.telegramUsername,
                telegramFirstName = userCrypto.decrypt(userId, user.telegramFirstName),
                email = userCrypto.decrypt(userId, user.email),
                createdAt = user.createdAt?.toString(),
            ),
            settings = settings?.let {
                SettingsExport(
                    displayName = userCrypto.decrypt(userId, it.displayName),
                    contextBlock = userCrypto.decrypt(userId, it.contextBlock),
                    timeZone = it.timeZone,
                    preferredLanguage = it.preferredLanguage,
                    calendarInviteEmail = it.calendarInviteEmail,
                    gender = it.gender,
                    agentDescription = userCrypto.decrypt(userId, it.agentDescription),
                    planningCron = it.planningCron,
                    weekStartDay = it.weekStartDay,
                    autoArchiveDays = it.autoArchiveDays,
                )
            },
            categories = categories.map {
                CategoryExport(
                    id = it.id.toString(),
                    label = it.label ?: "",
                    swatchId = it.swatchId?.name?.lowercase() ?: "",
                )
            },
            tags = tags.map {
                TagExport(
                    id = it.id.toString(),
                    label = it.label ?: "",
                    colorId = it.colorId?.name?.lowercase() ?: "",
                    description = it.description,
                )
            },
            tasks = tasks.map { task ->
                TaskExport(
                    id = task.id.toString(),
                    title = userCrypto.decrypt(userId, task.title) ?: "",
                    description = userCrypto.decrypt(userId, task.description),
                    url = task.url,
                    priority = task.priority?.name?.lowercase(),
                    deadline = task.deadline?.toString(),
                    estimatedMinutes = task.estimatedMinutes,
                    status = task.status?.name?.lowercase() ?: "",
                    categoryId = task.category?.id?.toString() ?: "",
                    tagIds = task.tags.map { it.id.toString() },
                    sortKey = task.sortKey ?: "",
                    createdAt = task.createdAt?.toString() ?: "",
                    updatedAt = task.updatedAt?.toString(),
                    relevantFrom = task.relevantFrom?.toString(),
                )
            },
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AccountService::class.java)
    }
}
