package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.CalendarSourceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalendarSourceRepository : JpaRepository<CalendarSourceEntity, UUID> {

    fun findAllByUserIdOrderByCreatedAtAsc(userId: UUID): List<CalendarSourceEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): CalendarSourceEntity?

    fun countByUserId(userId: UUID): Long
}
