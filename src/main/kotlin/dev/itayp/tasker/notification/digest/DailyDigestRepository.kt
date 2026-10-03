package dev.itayp.tasker.notification.digest

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface DailyDigestRepository : JpaRepository<DailyDigestEntity, UUID> {
    fun existsByUserIdAndDigestDate(userId: UUID, digestDate: LocalDate): Boolean
}
