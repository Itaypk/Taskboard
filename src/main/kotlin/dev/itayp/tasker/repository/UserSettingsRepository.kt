package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.UserSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSettingsRepository : JpaRepository<UserSettingsEntity, UUID> {
    fun findAllByPlanningCronIsNotNull(): List<UserSettingsEntity>
    fun findAllByAutoArchiveDaysIsNotNull(): List<UserSettingsEntity>

    /** Backs the AI tier cap check — how many accounts currently hold one of [tierNames]. */
    fun countByAiTierIn(tierNames: Collection<String>): Long
}
