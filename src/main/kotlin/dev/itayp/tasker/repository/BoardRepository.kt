package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.BoardEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BoardRepository : JpaRepository<BoardEntity, UUID>
