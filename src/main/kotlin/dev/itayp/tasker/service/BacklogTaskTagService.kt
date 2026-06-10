package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.toDomain
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class BacklogTaskTagService(
    private val tagRepository: BacklogTaskTagRepository,
    private val boardMembershipService: BoardMembershipService,
) {

    /**
     * Planner-facing bridge: resolves the user's sole board. Goes away when the planner becomes
     * board-aware (Phase 1 PR 3, `docs/BOARD-SHARING-PHASE1.md`).
     */
    fun getAllForUser(userId: UUID): List<BacklogTaskTag> =
        getTags(userId, boardMembershipService.resolveSoleBoard(userId))

    fun getTags(userId: UUID, boardId: UUID): List<BacklogTaskTag> {
        boardMembershipService.requireMember(userId, boardId)
        return tagRepository.findAllByBoardId(boardId).map { it.toDomain() }
    }
}
