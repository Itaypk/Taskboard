package dev.itayp.tasker.service

import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.repository.BoardMembershipRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

/**
 * Resolves and authorizes board access. Replaces the implicit "you can only touch your own rows"
 * model the app used before boards: callers operate by `board_id`, and these methods translate a
 * `user_id` into a board and assert membership.
 *
 * [resolveDefaultBoard] is the bridge for code paths that don't (yet) name a board explicitly —
 * the planning conversation, demo seeding, single-board import — so they keep working as the app
 * grows from one board per user to several. Web controllers name the board in the request path
 * and go through [requireMember] directly.
 */
@Service
class BoardMembershipService(
    private val boardMembershipRepository: BoardMembershipRepository,
) {

    /**
     * The user's default board: the oldest membership (`joined_at`, tie-broken by board id) — the
     * same ordering [BoardService.listBoardsForUser] surfaces first. Used by channel-less task
     * writes that don't carry a board (the planner's create-task tool, demo seeding). Throws if the
     * user has no board (registration always creates one).
     */
    @Transactional(readOnly = true)
    fun resolveDefaultBoard(userId: UUID): UUID {
        return boardMembershipRepository.findAllByUserId(userId)
            .minWithOrNull(compareBy({ it.joinedAt }, { it.boardId }))
            ?.boardId
            ?: throw IllegalStateException("User $userId has no board")
    }

    /** All board ids the user belongs to, default (oldest) first. For read paths that aggregate across boards. */
    @Transactional(readOnly = true)
    fun listBoardIds(userId: UUID): List<UUID> =
        boardMembershipRepository.findAllByUserId(userId)
            .sortedWith(compareBy({ it.joinedAt }, { it.boardId }))
            .mapNotNull { it.boardId }

    /** Asserts [userId] is a member of [boardId] and returns their role, else throws [BoardAccessDeniedException]. */
    @Transactional(readOnly = true)
    fun requireMember(userId: UUID, boardId: UUID): BoardRole {
        val membership = boardMembershipRepository.findByUserIdAndBoardId(userId, boardId)
            ?: throw BoardAccessDeniedException(userId, boardId)
        return membership.role!!
    }
}

/**
 * Thrown when a user attempts to access a board they are not a member of. Maps to HTTP 403.
 * Deliberately raised whether the board exists or not, so the response doesn't reveal board ids.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
class BoardAccessDeniedException(userId: UUID, boardId: UUID) :
    RuntimeException("User $userId is not a member of board $boardId")
