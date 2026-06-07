package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BoardMembershipEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BoardMembershipRepository : JpaRepository<BoardMembershipEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<BoardMembershipEntity>

    fun findAllByBoardId(boardId: UUID): List<BoardMembershipEntity>

    fun findByUserIdAndBoardId(userId: UUID, boardId: UUID): BoardMembershipEntity?

    fun deleteAllByBoardId(boardId: UUID)
}
