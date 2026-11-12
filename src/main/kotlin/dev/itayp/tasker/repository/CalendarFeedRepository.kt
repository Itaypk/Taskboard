package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.CalendarFeedEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalendarFeedRepository : JpaRepository<CalendarFeedEntity, UUID> {

    /** Feed lookup. The hash is unique, so this is at most one row. */
    fun findByTokenHash(tokenHash: String): CalendarFeedEntity?
}
