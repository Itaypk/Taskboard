package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BoardEntity
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardMascot
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.BoardRepository
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class BoardService(
    private val boardRepository: BoardRepository,
    private val boardMembershipRepository: BoardMembershipRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val boardCrypto: BoardCryptoService,
    private val boardMembershipService: BoardMembershipService,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {

    /**
     * Creates a board owned by [userId]: the board row, its DEK, an OWNER membership, and the
     * default category set (categories are board-owned). Returns the new board id. The board row is
     * persisted before [BoardCryptoService.ensureBoardKey] so the `board_data_key` FK is satisfied;
     * the name is encrypted only after the DEK exists.
     */
    @Transactional
    fun createBoardForOwner(userId: UUID, name: String): UUID {
        val now = Instant.now(clock)
        val boardId = UUID.randomUUID()
        val board = boardRepository.save(BoardEntity().apply {
            this.id = boardId
            this.mascot = BoardMascot.DEFAULT.id
            this.createdAt = now
        })
        boardCrypto.ensureBoardKey(boardId)
        board.name = boardCrypto.encrypt(boardId, name)
        boardRepository.save(board)

        boardMembershipRepository.save(BoardMembershipEntity().apply {
            this.id = UUID.randomUUID()
            this.boardId = boardId
            this.userId = userId
            this.role = BoardRole.OWNER
            this.joinedAt = now
        })

        UserService.DEFAULT_CATEGORIES.forEach { (label, color) ->
            categoryRepository.save(BacklogTaskCategoryEntity().apply {
                this.boardId = boardId
                this.label = label
                this.swatchId = color
            })
        }
        return boardId
    }

    /**
     * Creates a board owned by [userId] from a user-supplied name (validated/normalized), returning
     * its summary. The seed path ([createBoardForOwner]) is reused; the only addition is name
     * hygiene and a summary for the API/frontend.
     */
    @Transactional
    fun createBoard(userId: UUID, name: String): BoardSummary {
        val normalized = normalizeName(name)
        val boardId = createBoardForOwner(userId, normalized)
        val board = boardRepository.findById(boardId).orElseThrow()
        return BoardSummary(
            boardId, normalized, BoardRole.OWNER, board.createdAt!!,
            memberCount = 1, mascot = BoardMascot.normalize(board.mascot),
        )
    }

    /**
     * Updates a board the user **owns**: re-encrypts `board.name` under the board DEK and, when
     * [mascot] is non-null, sets the (cosmetic, plaintext) mascot. A null [mascot] leaves it as-is.
     */
    @Transactional
    fun updateBoard(userId: UUID, boardId: UUID, name: String, mascot: String?): BoardSummary {
        val role = boardMembershipService.requireMember(userId, boardId)
        requireOwner(role)
        val normalized = normalizeName(name)
        val board = boardRepository.findById(boardId)
            .orElseThrow { NoSuchElementException("Board $boardId not found") }
        board.name = boardCrypto.encrypt(boardId, normalized)
        if (mascot != null) board.mascot = BoardMascot.normalize(mascot)
        boardRepository.save(board)
        return BoardSummary(
            boardId, normalized, role, board.createdAt!!,
            memberCount = boardMembershipRepository.countByBoardId(boardId).toInt(),
            mascot = BoardMascot.normalize(board.mascot),
        )
    }

    /**
     * Deletes a board the user **owns**, with all its content (including the board's change feed and
     * watermark, which are board-keyed as of Phase 2). Refuses to delete the user's last board so
     * the ">= 1 board" invariant holds. The user's personal plans survive — the deletion mirrors the
     * board section of [AccountService.deleteUserData], kept in FK order via raw SQL.
     *
     * Phase 1 is single-member, so deleting clears the sole (OWNER) membership. Shared boards
     * (Phase 2) will instead transfer ownership rather than delete when other members remain.
     */
    @Transactional
    fun deleteBoard(userId: UUID, boardId: UUID) {
        val role = boardMembershipService.requireMember(userId, boardId)
        requireOwner(role)
        if (boardMembershipService.listBoardIds(userId).size <= 1) {
            throw LastBoardException()
        }
        jdbcTemplate.update(
            "DELETE FROM backlog_task_tags WHERE task_id IN (SELECT id FROM backlog_task WHERE board_id = ?)",
            boardId,
        )
        jdbcTemplate.update("DELETE FROM backlog_task WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_tag WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_category WHERE board_id = ?", boardId)
        // Change feed + watermark are board-keyed (Phase 2); they FK the board, so clear before it.
        jdbcTemplate.update("DELETE FROM backlog_task_change_event WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM backlog_task_watermark WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM board_invitation WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM board_membership WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM board_data_key WHERE board_id = ?", boardId)
        jdbcTemplate.update("DELETE FROM board WHERE id = ?", boardId)
    }

    /**
     * The user's boards, oldest membership first. That ordering is load-bearing: the first entry
     * is the **default board** (where channel-less task writes land — see
     * `docs/BOARD-SHARING-PHASE1.md`), and the frontend picks it as the initially active board.
     */
    @Transactional(readOnly = true)
    fun listBoardsForUser(userId: UUID): List<BoardSummary> {
        val memberships = boardMembershipRepository.findAllByUserId(userId)
            .sortedWith(compareBy({ it.joinedAt }, { it.boardId }))
        val boardsById = boardRepository.findAllById(memberships.mapNotNull { it.boardId }).associateBy { it.id }
        return memberships.mapNotNull { membership ->
            val board = boardsById[membership.boardId] ?: return@mapNotNull null
            BoardSummary(
                id = board.id!!,
                name = boardCrypto.decrypt(board.id!!, board.name) ?: "",
                role = membership.role!!,
                createdAt = board.createdAt!!,
                memberCount = boardMembershipRepository.countByBoardId(board.id!!).toInt(),
                mascot = BoardMascot.normalize(board.mascot),
            )
        }
    }

    private fun requireOwner(role: BoardRole) {
        if (role != BoardRole.OWNER) throw BoardOwnerRequiredException()
    }

    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Board name must not be blank" }
        require(trimmed.length <= MAX_BOARD_NAME_LENGTH) { "Board name must be at most $MAX_BOARD_NAME_LENGTH characters" }
        return trimmed
    }

    companion object {
        /** Name of the board auto-created for a new account. English-only; not user-facing in Phase 0. */
        const val DEFAULT_BOARD_NAME = "My tasks"
        const val MAX_BOARD_NAME_LENGTH = 60
    }
}

/** Thrown when a non-owner attempts an owner-only board action (rename/delete). Maps to HTTP 403. */
@ResponseStatus(HttpStatus.FORBIDDEN)
class BoardOwnerRequiredException : RuntimeException("Only the board owner may perform this action")

/** Thrown when deleting a board would leave the user with none. Maps to HTTP 409. */
@ResponseStatus(HttpStatus.CONFLICT)
class LastBoardException : RuntimeException("Cannot delete your last board")
