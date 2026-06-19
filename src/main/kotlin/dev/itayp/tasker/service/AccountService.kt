package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.model.response.BoardExport
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
    private val boardCrypto: BoardCryptoService,
    private val boardMembershipService: BoardMembershipService,
    private val boardService: BoardService,
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

        // §6 of docs/BOARD-SHARING.md: a board the user is the *sole* member of is deleted with its
        // content; a *shared* board survives their departure. Raw SQL (not JPA repo deletes) so each
        // statement executes immediately and in FK order — a deferred Hibernate flush would let a
        // `DELETE FROM board` run while board-owned rows still reference it (a Postgres FK violation).
        val boardIds: List<UUID> = jdbcTemplate.queryForList(
            "SELECT board_id FROM board_membership WHERE user_id = ?", UUID::class.java, userId,
        ).filterNotNull()
        val soleBoards = mutableListOf<UUID>()
        for (boardId in boardIds) {
            val memberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM board_membership WHERE board_id = ?", Int::class.java, boardId,
            ) ?: 0
            if (memberCount <= 1) soleBoards += boardId else departSharedBoard(userId, boardId)
        }
        soleBoards.forEach { deleteBoardContent(it) }

        settingsRepository.deleteById(userId)

        // User-owned audit rows. ai_usage_event FKs users(id) with no cascade, so it must be
        // cleared before the user row is deleted. (The change feed and watermark are board-keyed
        // now: sole boards' rows were deleted above; shared boards keep them with a nulled actor.)
        jdbcTemplate.update("DELETE FROM ai_usage_event WHERE user_id = ?", userId)
        // planned_task_slot → planned_task → planning_session; no user_id on slot, so use a subquery
        jdbcTemplate.update(
            "DELETE FROM planned_task_slot WHERE planned_task_id IN (SELECT id FROM planned_task WHERE user_id = ?)",
            userId,
        )
        jdbcTemplate.update("DELETE FROM planned_task WHERE user_id = ?", userId)
        // Per-user plan watermark FKs users(id) with no cascade.
        jdbcTemplate.update("DELETE FROM plan_watermark WHERE user_id = ?", userId)
        // planning_session has FKs to both users and ai_conversation
        jdbcTemplate.update("DELETE FROM planning_session WHERE user_id = ?", userId)
        // ai_message cascades automatically from ai_conversation (ON DELETE CASCADE in schema)
        jdbcTemplate.update("DELETE FROM ai_conversation WHERE user_id = ?", userId)

        // Invitations on surviving boards outlive their sender/acceptor (like the board itself), so
        // null the FK rather than delete the row. Sole-board invitations were already dropped above.
        jdbcTemplate.update("UPDATE board_invitation SET invited_by_user_id = NULL WHERE invited_by_user_id = ?", userId)
        jdbcTemplate.update("UPDATE board_invitation SET accepted_by_user_id = NULL WHERE accepted_by_user_id = ?", userId)

        // auth_identities and user_data_key both FK to users; remove last so the user row delete can proceed.
        jdbcTemplate.update("DELETE FROM auth_identities WHERE user_id = ?", userId)
        jdbcTemplate.update("DELETE FROM user_data_key WHERE user_id = ?", userId)

        // Sole-board rows last: their content is gone and board_membership FKs users, so they must be
        // cleared before the user row delete can proceed.
        for (boardId in soleBoards) {
            jdbcTemplate.update("DELETE FROM board_membership WHERE board_id = ?", boardId)
            jdbcTemplate.update("DELETE FROM board_data_key WHERE board_id = ?", boardId)
            jdbcTemplate.update("DELETE FROM board WHERE id = ?", boardId)
        }
    }

    /**
     * The user leaves a board that other members keep. If they were its only OWNER, the
     * longest-tenured remaining member is auto-promoted first (§6). The board's history survives:
     * the user's claims are cleared and their change-event actor is nulled (not deleted).
     */
    private fun departSharedBoard(userId: UUID, boardId: UUID) {
        val isOwner = (jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM board_membership WHERE board_id = ? AND user_id = ? AND role = 'OWNER'",
            Int::class.java, boardId, userId,
        ) ?: 0) > 0
        if (isOwner) {
            val otherOwners = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM board_membership WHERE board_id = ? AND role = 'OWNER' AND user_id <> ?",
                Int::class.java, boardId, userId,
            ) ?: 0
            if (otherOwners == 0) {
                val nextOwner = jdbcTemplate.queryForList(
                    "SELECT user_id FROM board_membership WHERE board_id = ? AND user_id <> ? " +
                        "ORDER BY joined_at ASC, user_id ASC",
                    UUID::class.java, boardId, userId,
                ).firstOrNull()
                if (nextOwner != null) {
                    jdbcTemplate.update(
                        "UPDATE board_membership SET role = 'OWNER' WHERE board_id = ? AND user_id = ?",
                        boardId, nextOwner,
                    )
                }
            }
        }
        jdbcTemplate.update("DELETE FROM board_membership WHERE board_id = ? AND user_id = ?", boardId, userId)
        jdbcTemplate.update(
            "UPDATE backlog_task SET assignee_user_id = NULL WHERE board_id = ? AND assignee_user_id = ?",
            boardId, userId,
        )
        jdbcTemplate.update(
            "UPDATE backlog_task_change_event SET actor_user_id = NULL WHERE board_id = ? AND actor_user_id = ?",
            boardId, userId,
        )
    }

    /** Deletes every board-owned row for a board (used when the departing user was its sole member). */
    private fun deleteBoardContent(boardId: UUID) {
        // The task join table has no ON DELETE CASCADE, so its rows go before the tasks.
        jdbcTemplate.update(
            "DELETE FROM backlog_task_tags WHERE task_id IN (SELECT id FROM backlog_task WHERE board_id = ?)",
            boardId,
        )
        jdbcTemplate.update("DELETE FROM backlog_task WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_tag WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_category WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_change_event WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_watermark WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM board_invitation WHERE board_id = ?", boardId)
    }

    /**
     * True iff the account holds nothing beyond what registration auto-seeds. Used by
     * the import flow to reject a 409 attempt to import on top of existing user data:
     * import wipes the seeded categories and overwrites settings, so anything else is
     * work the user did and we must not clobber it.
     */
    @Transactional(readOnly = true)
    fun isEmptyForImport(userId: UUID): Boolean {
        // A freshly-registered account has exactly one board (the seeded default). Any extra board
        // is work the user did, so import must refuse rather than clobber it.
        val boardIds = boardMembershipService.listBoardIds(userId)
        if (boardIds.size != 1) return false
        val boardId = boardIds.single()
        if (taskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId).isNotEmpty()) return false
        if (tagRepository.findAllByBoardId(boardId).isNotEmpty()) return false
        val expected = UserService.DEFAULT_CATEGORIES.toSet()
        val actual = categoryRepository.findAllByBoardId(boardId).mapNotNull { entity ->
            val label = entity.label ?: return@mapNotNull null
            val swatch = entity.swatchId ?: return@mapNotNull null
            label to swatch
        }.toSet()
        return actual == expected
    }

    @Transactional(readOnly = true)
    fun exportAccount(userId: UUID): AccountExportResponse {
        // Every board the user belongs to is emitted (default first). Task content is decrypted with
        // the owning board's DEK; user/settings stay under the user DEK.
        val user = userRepository.findById(userId).orElseThrow()
        val settings = settingsRepository.findById(userId).orElse(null)

        val boards = boardService.listBoardsForUser(userId).map { board ->
            val categories = categoryRepository.findAllByBoardId(board.id)
            val tags = tagRepository.findAllByBoardId(board.id)
            val tasks = taskRepository.findAllByBoardIdOrderBySortKeyAsc(board.id)
            BoardExport(
                name = board.name,
                role = board.role.name,
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
                        title = boardCrypto.decrypt(board.id, task.title) ?: "",
                        description = boardCrypto.decrypt(board.id, task.description),
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
                        assignee = task.assigneeUserId?.toString(),
                    )
                },
            )
        }

        return AccountExportResponse(
            formatVersion = 2,
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
                    aiEnabled = it.aiEnabled,
                    aiTier = it.aiTier,
                )
            },
            boards = boards,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AccountService::class.java)
    }
}
