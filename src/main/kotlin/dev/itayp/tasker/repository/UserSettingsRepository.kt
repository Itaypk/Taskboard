package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.UserSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSettingsRepository : JpaRepository<UserSettingsEntity, UUID>
