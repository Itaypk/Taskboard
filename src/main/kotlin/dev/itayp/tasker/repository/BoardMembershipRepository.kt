package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BoardMembershipRepository : JpaRepository<BoardMembershipEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<BoardMembershipEntity>

    fun findAllByBoardId(boardId: UUID): List<BoardMembershipEntity>

    fun findByUserIdAndBoardId(userId: UUID, boardId: UUID): BoardMembershipEntity?

    /** Owner count for a board — backs the "≥ 1 OWNER" invariant guards. */
    fun countByBoardIdAndRole(boardId: UUID, role: BoardRole): Long

    fun deleteAllByBoardId(boardId: UUID)
}
