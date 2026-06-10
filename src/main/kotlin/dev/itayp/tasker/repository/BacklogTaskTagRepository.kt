package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BacklogTaskTagRepository : JpaRepository<BacklogTaskTagEntity, UUID> {

    fun findAllByBoardId(boardId: UUID): List<BacklogTaskTagEntity>

    fun deleteAllByBoardId(boardId: UUID)
}
