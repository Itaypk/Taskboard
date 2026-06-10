package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BoardEntity
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.BoardRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class BoardService(
    private val boardRepository: BoardRepository,
    private val boardMembershipRepository: BoardMembershipRepository,
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val boardCrypto: BoardCryptoService,
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
            )
        }
    }

    companion object {
        /** Name of the board auto-created for a new account. English-only; not user-facing in Phase 0. */
        const val DEFAULT_BOARD_NAME = "My tasks"
    }
}
