package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Rebuilds a user's content from a previously-exported [AccountExportResponse].
 *
 * Designed for the encryption-migration flow: an account exports on the old build,
 * the DB is wiped, the user re-authenticates on the new build, then calls import.
 * The new account starts with auto-seeded defaults; import wipes those and writes
 * the exported state into the new userId. Format spec: `docs/export-format-v1.md`.
 */
@Service
class AccountImportService(
    private val accountService: AccountService,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val userSettingsService: UserSettingsService,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val tagRepository: BacklogTaskTagRepository,
    private val taskRepository: BacklogTaskRepository,
    private val userCrypto: UserCryptoService,
) {
    private val log = LoggerFactory.getLogger(AccountImportService::class.java)

    @Transactional
    fun import(userId: UUID, payload: AccountExportResponse): ImportSummary {
        require(payload.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "Unsupported export formatVersion: ${payload.formatVersion} (expected $SUPPORTED_FORMAT_VERSION)"
        }
        check(accountService.isEmptyForImport(userId)) {
            "Account already has user data; import is only valid on a fresh account"
        }

        // Step 1: wipe the auto-seeded defaults. With isEmptyForImport already verified,
        // there are no tasks/tags blocking the category delete.
        categoryRepository.deleteAllByUserId(userId)

        // Step 2: categories — keep an old→new UUID map so task FKs can be retargeted.
        val categoryIdMap = HashMap<String, UUID>(payload.categories.size)
        for (cat in payload.categories) {
            val swatch = runCatching { CategoryColor.valueOf(cat.swatchId.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown category swatchId: ${cat.swatchId}") }
            val saved = categoryRepository.save(BacklogTaskCategoryEntity().apply {
                this.userId = userId
                this.label = cat.label
                this.swatchId = swatch
            })
            categoryIdMap[cat.id] = saved.id!!
        }

        // Step 3: tags — same id-map pattern. We also keep the entities directly so step 4
        // can attach them to task.tags without a second `findAll` round-trip.
        val tagsByOldId = HashMap<String, BacklogTaskTagEntity>(payload.tags.size)
        for (tag in payload.tags) {
            val color = runCatching { TagColor.valueOf(tag.colorId.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown tag colorId: ${tag.colorId}") }
            val saved = tagRepository.save(BacklogTaskTagEntity().apply {
                this.userId = userId
                this.label = tag.label
                this.colorId = color
                this.description = tag.description
            })
            tagsByOldId[tag.id] = saved
        }

        // Step 4: tasks. Encrypt sensitive fields, translate FKs, parse dates.
        for (task in payload.tasks) {
            val newCategoryId = categoryIdMap[task.categoryId]
                ?: throw IllegalArgumentException("Task ${task.id} references unknown categoryId ${task.categoryId}")
            val category = categoryRepository.findByIdAndUserId(newCategoryId, userId)
                ?: error("Category $newCategoryId was just persisted but cannot be loaded")

            val tagEntities = task.tagIds.map { oldId ->
                tagsByOldId[oldId]
                    ?: throw IllegalArgumentException("Task ${task.id} references unknown tagId $oldId")
            }

            val status = parseEnum<TaskStatus>("status", task.status)
            val priority = task.priority?.let { parseEnum<TaskPriority>("priority", it) }

            taskRepository.save(BacklogTaskEntity().apply {
                this.userId = userId
                this.title = userCrypto.encrypt(userId, task.title)
                this.description = userCrypto.encrypt(userId, task.description)
                this.url = task.url
                this.priority = priority
                this.deadline = parseLocalDate("deadline", task.deadline)
                this.estimatedMinutes = task.estimatedMinutes
                this.status = status
                this.category = category
                this.tags = tagEntities.toMutableSet()
                this.sortKey = task.sortKey
                this.createdAt = parseInstant("createdAt", task.createdAt)
                    ?: throw IllegalArgumentException("Task ${task.id} has invalid createdAt: ${task.createdAt}")
                this.updatedAt = parseInstant("updatedAt", task.updatedAt)
                this.rescheduleCount = 0
                this.lastScheduledInSessionId = null
                this.relevantFrom = parseLocalDate("relevantFrom", task.relevantFrom)
            })
        }

        // Step 5: user-level fields (firstName, email, emailHash). emailVerifiedAt
        // intentionally stays null — re-verification runs on the new deployment.
        val user = userRepository.findById(userId).orElseThrow {
            IllegalStateException("Authenticated user $userId not found")
        }
        user.telegramFirstName = userCrypto.encrypt(userId, payload.user.telegramFirstName)
        val normalisedEmail = payload.user.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalisedEmail != null) {
            user.email = userCrypto.encrypt(userId, normalisedEmail)
            user.emailHash = EmailHasher.hash(normalisedEmail)
        }
        userRepository.save(user)

        // Step 6: settings — load the auto-created row and overwrite in place.
        payload.settings?.let { s ->
            userSettingsService.validateSettingsInput(
                timeZone = s.timeZone,
                preferredLanguage = s.preferredLanguage,
                gender = s.gender,
                planningCron = s.planningCron,
                weekStartDay = s.weekStartDay,
            )
            val settings = userSettingsRepository.findById(userId)
                .orElseGet { UserSettingsEntity().apply { this.userId = userId } }
            settings.displayName = userCrypto.encrypt(userId, s.displayName)
            settings.contextBlock = userCrypto.encrypt(userId, s.contextBlock)
            settings.timeZone = s.timeZone
            settings.preferredLanguage = s.preferredLanguage
            settings.calendarInviteEmail = s.calendarInviteEmail
            settings.gender = s.gender
            settings.agentDescription = userCrypto.encrypt(userId, s.agentDescription)
            settings.planningCron = s.planningCron
            settings.weekStartDay = s.weekStartDay
            settings.autoArchiveDays = s.autoArchiveDays
            userSettingsRepository.save(settings)
        }

        val summary = ImportSummary(
            categories = payload.categories.size,
            tags = payload.tags.size,
            tasks = payload.tasks.size,
        )
        log.info("Imported account for user {}: {}", userId, summary)
        return summary
    }

    private inline fun <reified E : Enum<E>> parseEnum(field: String, raw: String): E =
        runCatching { enumValueOf<E>(raw.uppercase()) }
            .getOrElse { throw IllegalArgumentException("Invalid $field value: $raw") }

    private fun parseLocalDate(field: String, raw: String?): LocalDate? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { LocalDate.parse(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid $field date: $raw") }
    }

    private fun parseInstant(field: String, raw: String?): Instant? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { Instant.parse(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid $field timestamp: $raw") }
    }

    companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
    }
}

data class ImportSummary(
    val categories: Int,
    val tags: Int,
    val tasks: Int,
)
