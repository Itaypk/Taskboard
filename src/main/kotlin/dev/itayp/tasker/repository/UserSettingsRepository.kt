package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.UserSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserSettingsRepository : JpaRepository<UserSettingsEntity, UUID> {
    fun findAllByPlanningCronIsNotNull(): List<UserSettingsEntity>
    fun findAllByAutoArchiveDaysIsNotNull(): List<UserSettingsEntity>
    fun findAllByDailyDigestEnabledTrue(): List<UserSettingsEntity>

    /** Backs the AI tier cap check — how many accounts currently hold one of [tierNames]. */
    fun countByAiTierIn(tierNames: Collection<String>): Long

    /**
     * Backs the `tasker.users.ai_enabled` gauge: accounts with AI switched on in their settings and
     * a tier that grants it. [noneTier] is passed in rather than inlined so the tier name has one home.
     */
    @Query(
        """
        SELECT COUNT(s) FROM UserSettingsEntity s JOIN UserEntity u ON u.id = s.userId
        WHERE u.claimed = :claimed AND s.aiEnabled = true AND s.aiTier <> :noneTier
        """,
    )
    fun countAiEnabled(@Param("claimed") claimed: Boolean, @Param("noneTier") noneTier: String): Long
}
