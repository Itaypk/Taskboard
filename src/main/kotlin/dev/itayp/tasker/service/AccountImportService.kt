package dev.itayp.tasker.service

import dev.itayp.tasker.ai.access.AiTier
import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserEntity
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
 * the exported state into the new userId. Format spec: `docs/export-format-v3.md`.
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
        if (payload.formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw ImportException(
                ImportErrorCategory.UNSUPPORTED_VERSION,
                "Unsupported export formatVersion: ${payload.formatVersion} (expected $SUPPORTED_FORMAT_VERSION)",
            )
        }
        // A well-formed export always has at least one board; an empty list is a corrupted file.
        if (payload.boards.isEmpty()) {
            throw ImportException(ImportErrorCategory.CORRUPTED_FILE, "Import payload must contain at least one board")
        }
        if (!accountService.isEmptyForImport(userId)) {
            throw ImportException(
                ImportErrorCategory.ACCOUNT_NOT_EMPTY,
                "Account already has user data; import is only valid on a fresh account",
            )
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
        val emailOutcome = importEmail(userId, user, payload.user.email)
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
            settings.appReminders = s.appReminders
            settings.gender = s.gender
            settings.agentDescription = userCrypto.encrypt(userId, s.agentDescription)
            settings.planningCron = s.planningCron
            settings.weekStartDay = s.weekStartDay
            settings.autoArchiveDays = s.autoArchiveDays
            // ai_tier is admin-controlled: import is untrusted user data and must not let a user
            // assign themselves a higher tier. The existing row's tier stays as-is (whatever the
            // registration-time cap grant / a manual admin grant left it at) — so aiEnabled is
            // re-clamped against it too, the same guard UserSettingsService.update() applies.
            settings.aiEnabled = s.aiEnabled && AiTier.fromName(settings.aiTier).grantsAccess
            settings.aiEnhancedReminders = s.aiEnhancedReminders
            userSettingsRepository.save(settings)
        }

        val summary = totals.copy(
            emailImported = emailOutcome == EmailImportOutcome.IMPORTED,
            emailSkipReason = emailOutcome.skipReason,
        )
        log.info("Imported account for user {}: {}", userId, summary)
        return summary
    }

    /**
     * Email is *identity*, not portable content: it backs the unique `email_hash` handle and a
     * login method. Adopting the exported email is only safe on a fresh account that has no email
     * of its own and where no other account already holds that address. Otherwise we keep the
     * importing account's own email and report why, rather than clobbering identity or 500-ing on
     * the unique constraint. (No `auth_identities` row is created here — `emailVerifiedAt` stays
     * null and the user re-verifies to turn it into a login method.)
     */
    private fun importEmail(userId: UUID, user: UserEntity, rawEmail: String?): EmailImportOutcome {
        val normalisedEmail = rawEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return EmailImportOutcome.NONE
        val hash = EmailHasher.hash(normalisedEmail)
        return when {
            // Already this account's email (re-import of own export): nothing to do, not a skip.
            user.emailHash == hash -> EmailImportOutcome.NONE
            // Account already carries a different email — don't overwrite its own identity.
            user.emailHash != null -> EmailImportOutcome.SKIPPED_ACCOUNT_HAS_EMAIL
            // Address belongs to another live account — adopting it would hijack their login.
            userRepository.findByEmailHash(hash)?.let { it.id != userId } == true ->
                EmailImportOutcome.SKIPPED_TAKEN
            else -> {
                user.email = userCrypto.encrypt(userId, normalisedEmail)
                user.emailHash = hash
                EmailImportOutcome.IMPORTED
            }
        }
    }

    /**
     * Writes one exported board's categories/tags/tasks into [boardId] (which already exists, freshly
     * seeded). Wipes the seeded categories first, then rebuilds; tasks resolve their category/tags
     * by position in the exported arrays (v3). Returns the per-board counts so the caller can
     * aggregate across all imported boards.
     */
    private fun importBoardContent(boardId: UUID, board: BoardExport): ImportSummary {
        // Clear the seeded tutorial tasks first: they reference the default categories, so they must go
        // before the category wipe below (flush so the row deletes hit the DB ahead of the bulk delete).
        // isEmptyForImport guarantees the only tasks here are tutorial ones.
        val tutorialTasks = taskRepository.findAllByBoardIdAndTutorialTrue(boardId)
        if (tutorialTasks.isNotEmpty()) {
            taskRepository.deleteAll(tutorialTasks)
            taskRepository.flush()
        }

        // Wipe the auto-seeded default categories; nothing references them once tutorial tasks are gone.
        categoryRepository.deleteAllByBoardId(boardId)

        // Categories — saved in export order so tasks can resolve them by position (v3).
        val categoriesByIndex = board.categories.map { cat ->
            val swatch = runCatching { CategoryColor.valueOf(cat.swatchId.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown category swatchId: ${cat.swatchId}") }
            categoryRepository.save(BacklogTaskCategoryEntity().apply {
                this.boardId = boardId
                this.label = cat.label
                this.swatchId = swatch
            })
        }

        // Tags — same positional scheme; keep the entities so tasks can attach them without a re-read.
        val tagsByIndex = board.tags.map { tag ->
            val color = runCatching { TagColor.valueOf(tag.colorId.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown tag colorId: ${tag.colorId}") }
            tagRepository.save(BacklogTaskTagEntity().apply {
                this.boardId = boardId
                this.label = tag.label
                this.colorId = color
                this.description = tag.description
            })
        }

        // Tasks. Encrypt sensitive fields under this board's DEK, resolve category/tag positions,
        // parse dates. Cross-account assignment isn't carried over: the board is created fresh with
        // the importer as sole OWNER (see docs/export-format-v3.md), so tasks come in unassigned.
        for (task in board.tasks) {
            val category = categoriesByIndex.getOrNull(task.categoryIndex)
                ?: throw IllegalArgumentException("Task references out-of-range categoryIndex ${task.categoryIndex}")

            val tagEntities = task.tagIndexes.map { idx ->
                tagsByIndex.getOrNull(idx)
                    ?: throw IllegalArgumentException("Task references out-of-range tagIndex $idx")
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
                    ?: throw IllegalArgumentException("Task '${task.title}' has invalid createdAt: ${task.createdAt}")
                this.updatedAt = parseInstant("updatedAt", task.updatedAt)
                this.rescheduleCount = 0
                this.lastScheduledInSessionId = null
                this.relevantFrom = parseLocalDate("relevantFrom", task.relevantFrom)
                this.hiddenFromAssistant = task.hiddenFromAssistant
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
        const val SUPPORTED_FORMAT_VERSION = 3
    }
}

/** Outcome of trying to adopt the exported email onto the importing account. */
private enum class EmailImportOutcome(val skipReason: String?) {
    /** No email in the export, or it already matches the account's own — nothing reported. */
    NONE(null),
    /** Exported email written onto the account. */
    IMPORTED(null),
    /** Account already had its own (different) email; kept it. */
    SKIPPED_ACCOUNT_HAS_EMAIL("ACCOUNT_HAS_EMAIL"),
    /** Exported email already belongs to another account; kept the importer's own. */
    SKIPPED_TAKEN("TAKEN"),
}

data class ImportSummary(
    val categories: Int,
    val tags: Int,
    val tasks: Int,
    /** True when the exported email was adopted onto the account. */
    val emailImported: Boolean = false,
    /**
     * Stable reason code when the exported email was *not* imported (`ACCOUNT_HAS_EMAIL` | `TAKEN`),
     * or null when there was nothing to report. The frontend maps it to a human message.
     */
    val emailSkipReason: String? = null,
) {
    // Only board counts aggregate; email outcome is user-level and set once at the end of import.
    operator fun plus(other: ImportSummary) =
        ImportSummary(categories + other.categories, tags + other.tags, tasks + other.tasks)
}
