package dev.itayp.tasker.service

import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.repository.BoardMembershipRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Resolves and authorizes board access. Replaces the implicit "you can only touch your own rows"
 * model the app used before boards: callers operate by `board_id`, and these methods translate a
 * `user_id` into a board and assert membership.
 *
 * Phase 0 invariant: every user owns exactly one board, so [resolveSoleBoard] is the bridge that
 * lets the existing user-id-keyed controllers keep working without an API change.
 */
@Service
class BoardMembershipService(
    private val boardMembershipRepository: BoardMembershipRepository,
) {

    /**
     * The board the user belongs to. Phase-0 contract: exactly one. Throws if a user has none
     * (registration always creates one) or — defensively — more than one (not yet reachable until
     * multi-board ships in Phase 1).
     */
    @Transactional(readOnly = true)
    fun resolveSoleBoard(userId: UUID): UUID {
        val memberships = boardMembershipRepository.findAllByUserId(userId)
        return when (memberships.size) {
            1 -> memberships.first().boardId!!
            0 -> throw IllegalStateException("User $userId has no board")
            else -> throw IllegalStateException("User $userId belongs to multiple boards; multi-board is not enabled")
        }
    }

    /** Asserts [userId] is a member of [boardId] and returns their role, else throws [BoardAccessDeniedException]. */
    @Transactional(readOnly = true)
    fun requireMember(userId: UUID, boardId: UUID): BoardRole {
        val membership = boardMembershipRepository.findByUserIdAndBoardId(userId, boardId)
            ?: throw BoardAccessDeniedException(userId, boardId)
        return membership.role!!
    }
}

/** Thrown when a user attempts to access a board they are not a member of. Maps to HTTP 403. */
class BoardAccessDeniedException(userId: UUID, boardId: UUID) :
    RuntimeException("User $userId is not a member of board $boardId")
