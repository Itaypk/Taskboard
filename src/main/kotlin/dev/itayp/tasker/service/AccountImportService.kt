package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
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
import dev.itayp.tasker.model.response.BoardExport
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
    private val boardCrypto: BoardCryptoService,
    private val boardMembershipService: BoardMembershipService,
    private val boardService: BoardService,
) {
    private val log = LoggerFactory.getLogger(AccountImportService::class.java)

    @Transactional
    fun import(userId: UUID, payload: AccountExportResponse): ImportSummary {
        require(payload.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "Unsupported export formatVersion: ${payload.formatVersion} (expected $SUPPORTED_FORMAT_VERSION)"
        }
        require(payload.boards.isNotEmpty()) {
            "Import payload must contain at least one board"
        }
        check(accountService.isEmptyForImport(userId)) {
            "Account already has user data; import is only valid on a fresh account"
        }

        // The first exported board reuses the account's existing default board (isEmptyForImport
        // guaranteed it's the lone, freshly-seeded one); any further boards are created fresh. Task
        // title/description are encrypted under the owning board's DEK; user/settings stay under the
        // user DEK.
        val defaultBoardId = boardMembershipService.resolveDefaultBoard(userId)
        var totals = importBoardContent(defaultBoardId, payload.boards.first())
        for (board in payload.boards.drop(1)) {
            val name = board.name.ifBlank { BoardService.DEFAULT_BOARD_NAME }
            val newBoardId = boardService.createBoard(userId, name).id
            totals += importBoardContent(newBoardId, board)
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
            settings.aiEnabled = s.aiEnabled
            // ai_tier is admin-controlled: import is untrusted user data and must not let a user
            // assign themselves a higher tier. The existing row's tier stays as-is (default "standard").
            userSettingsRepository.save(settings)
        }

        log.info("Imported account for user {}: {}", userId, totals)
        return totals
    }

    /**
     * Writes one exported board's categories/tags/tasks into [boardId] (which already exists, freshly
     * seeded). Wipes the seeded categories first, then rebuilds with old→new id remapping. Returns
     * the per-board counts so the caller can aggregate across all imported boards.
     */
    private fun importBoardContent(boardId: UUID, board: BoardExport): ImportSummary {
        // Wipe the auto-seeded defaults. The account is verified empty, so nothing references them.
        categoryRepository.deleteAllByBoardId(boardId)

        // Categories — keep an old→new UUID map so task FKs can be retargeted.
        val categoryIdMap = HashMap<String, UUID>(board.categories.size)
        for (cat in board.categories) {
            val swatch = runCatching { CategoryColor.valueOf(cat.swatchId.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown category swatchId: ${cat.swatchId}") }
            val saved = categoryRepository.save(BacklogTaskCategoryEntity().apply {
                this.boardId = boardId
                this.label = cat.label
                this.swatchId = swatch
            })
            categoryIdMap[cat.id] = saved.id!!
        }

        // Tags — same id-map pattern; keep the entities so tasks can attach them without a re-read.
        val tagsByOldId = HashMap<String, BacklogTaskTagEntity>(board.tags.size)
        for (tag in board.tags) {
            val color = runCatching { TagColor.valueOf(tag.colorId.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown tag colorId: ${tag.colorId}") }
            val saved = tagRepository.save(BacklogTaskTagEntity().apply {
                this.boardId = boardId
                this.label = tag.label
                this.colorId = color
                this.description = tag.description
            })
            tagsByOldId[tag.id] = saved
        }

        // Tasks. Encrypt sensitive fields under this board's DEK, translate FKs, parse dates.
        // The exported `assignee` (a cross-account user id) is deliberately dropped on import: it's
        // meaningless in the importing account's board, which is created fresh with the importer as
        // sole OWNER (see docs/export-format-v2.md). Tasks come in unassigned.
        for (task in board.tasks) {
            val newCategoryId = categoryIdMap[task.categoryId]
                ?: throw IllegalArgumentException("Task ${task.id} references unknown categoryId ${task.categoryId}")
            val category = categoryRepository.findByIdAndBoardId(newCategoryId, boardId)
                ?: error("Category $newCategoryId was just persisted but cannot be loaded")

            val tagEntities = task.tagIds.map { oldId ->
                tagsByOldId[oldId]
                    ?: throw IllegalArgumentException("Task ${task.id} references unknown tagId $oldId")
            }

            val status = parseEnum<TaskStatus>("status", task.status)
            val priority = task.priority?.let { parseEnum<TaskPriority>("priority", it) }

            taskRepository.save(BacklogTaskEntity().apply {
                this.boardId = boardId
                this.title = boardCrypto.encrypt(boardId, task.title)
                this.description = boardCrypto.encrypt(boardId, task.description)
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

        return ImportSummary(
            categories = board.categories.size,
            tags = board.tags.size,
            tasks = board.tasks.size,
        )
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
        const val SUPPORTED_FORMAT_VERSION = 2
    }
}

data class ImportSummary(
    val categories: Int,
    val tags: Int,
    val tasks: Int,
) {
    operator fun plus(other: ImportSummary) =
        ImportSummary(categories + other.categories, tags + other.tags, tasks + other.tasks)
}
