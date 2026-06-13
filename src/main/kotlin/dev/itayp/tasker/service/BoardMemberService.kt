package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardMember
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

/**
 * Member management for shared boards: listing members, changing roles, removing members, and
 * leaving. The board invariant is **≥ 1 OWNER** (not "exactly one" — households want co-owners, see
 * docs/BOARD-SHARING-PHASE2.md, Decision 5). Owner departures auto-promote the longest-tenured
 * remaining member so a board is never left ownerless (Decision 6).
 *
 * Authorization for board content stays in [BoardMembershipService]; this service layers the
 * owner-only management actions and the leave/removal side effects (clearing the departed member's
 * claims) on top.
 */
@Service
class BoardMemberService(
    private val boardMembershipRepository: BoardMembershipRepository,
    private val boardMembershipService: BoardMembershipService,
    private val backlogTaskRepository: BacklogTaskRepository,
    private val displayNameResolver: MemberDisplayNameResolver,
) {

    private val log = LoggerFactory.getLogger(BoardMemberService::class.java)

    /** Members of [boardId], oldest first, with resolved display names. Any member may read. */
    @Transactional(readOnly = true)
    fun listMembers(userId: UUID, boardId: UUID): List<BoardMember> {
        boardMembershipService.requireMember(userId, boardId)
        val memberships = boardMembershipRepository.findAllByBoardId(boardId)
            .sortedWith(compareBy({ it.joinedAt }, { it.userId }))
        val names = displayNameResolver.resolve(memberships.mapNotNull { it.userId })
        return memberships.map {
            BoardMember(
                userId = it.userId!!,
                role = it.role!!,
                joinedAt = it.joinedAt!!,
                displayName = names[it.userId] ?: "Member",
            )
        }
    }

    /** Promotes/demotes [targetUserId] (owner-only). Refuses demoting the last OWNER. */
    @Transactional
    fun setRole(userId: UUID, boardId: UUID, targetUserId: UUID, role: BoardRole) {
        requireOwner(userId, boardId)
        val target = boardMembershipRepository.findByUserIdAndBoardId(targetUserId, boardId)
            ?: throw MemberNotFoundException()
        if (target.role == role) return
        if (role != BoardRole.OWNER && target.role == BoardRole.OWNER && isLastOwner(boardId)) {
            throw LastOwnerException()
        }
        target.role = role
        boardMembershipRepository.save(target)
        log.info("Board {} member {} role set to {} by {}", boardId, targetUserId, role, userId)
    }

    /** Removes [targetUserId] from the board (owner-only). Not for self — use [leaveBoard]. */
    @Transactional
    fun removeMember(userId: UUID, boardId: UUID, targetUserId: UUID) {
        requireOwner(userId, boardId)
        if (targetUserId == userId) throw CannotRemoveSelfException()
        val target = boardMembershipRepository.findByUserIdAndBoardId(targetUserId, boardId)
            ?: throw MemberNotFoundException()
        if (target.role == BoardRole.OWNER && isLastOwner(boardId)) throw LastOwnerException()
        removeMembershipWithSideEffects(target)
        log.info("Board {} member {} removed by {}", boardId, targetUserId, userId)
    }

    /**
     * The caller leaves [boardId]. A *sole* member cannot leave (deleting the board is the honest
     * action); leaving your *last* board is refused to preserve the ≥1-board invariant. A departing
     * sole OWNER with other members auto-promotes the longest-tenured remaining member first.
     */
    @Transactional
    fun leaveBoard(userId: UUID, boardId: UUID) {
        val membership = boardMembershipRepository.findByUserIdAndBoardId(userId, boardId)
            ?: throw BoardAccessDeniedException(userId, boardId)
        val members = boardMembershipRepository.findAllByBoardId(boardId)
        if (members.size <= 1) throw SoleMemberException()
        if (boardMembershipService.listBoardIds(userId).size <= 1) throw LastBoardException()
        if (membership.role == BoardRole.OWNER && isLastOwner(boardId)) {
            promoteLongestTenured(boardId, excluding = userId)
        }
        removeMembershipWithSideEffects(membership)
        log.info("User {} left board {}", userId, boardId)
    }

    /** Deletes a membership row and clears any claims the member held on the board. */
    private fun removeMembershipWithSideEffects(membership: BoardMembershipEntity) {
        backlogTaskRepository.clearAssigneeOnBoardForUser(membership.boardId!!, membership.userId!!)
        boardMembershipRepository.delete(membership)
    }

    /** Promotes the longest-tenured member other than [excluding] to OWNER (min joinedAt, tie-broken by user id). */
    private fun promoteLongestTenured(boardId: UUID, excluding: UUID) {
        val next = boardMembershipRepository.findAllByBoardId(boardId)
            .filter { it.userId != excluding }
            .minWithOrNull(compareBy({ it.joinedAt }, { it.userId }))
            ?: return
        next.role = BoardRole.OWNER
        boardMembershipRepository.save(next)
        log.info("Board {} auto-promoted member {} to OWNER", boardId, next.userId)
    }

    private fun isLastOwner(boardId: UUID): Boolean =
        boardMembershipRepository.countByBoardIdAndRole(boardId, BoardRole.OWNER) <= 1

    private fun requireOwner(userId: UUID, boardId: UUID) {
        if (boardMembershipService.requireMember(userId, boardId) != BoardRole.OWNER) {
            throw BoardOwnerRequiredException()
        }
    }
}

/** Thrown when an action would leave a board without an OWNER. Maps to HTTP 409. */
@ResponseStatus(HttpStatus.CONFLICT)
class LastOwnerException : RuntimeException("Cannot demote or remove the last owner of a board")

/** Thrown when the sole member of a board tries to leave it (they should delete it). Maps to HTTP 409. */
@ResponseStatus(HttpStatus.CONFLICT)
class SoleMemberException : RuntimeException("You are the only member; delete the board instead")

/** Thrown when a targeted member is not on the board. Maps to HTTP 404. */
@ResponseStatus(HttpStatus.NOT_FOUND)
class MemberNotFoundException : RuntimeException("Member not found on this board")

/** Thrown when an owner tries to remove themselves via the member-removal endpoint. Maps to HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class CannotRemoveSelfException : RuntimeException("Use leave to remove yourself from a board")
