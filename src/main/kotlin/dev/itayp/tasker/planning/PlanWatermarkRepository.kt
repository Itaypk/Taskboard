package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PlanWatermarkRepository : JpaRepository<PlanWatermarkEntity, UUID>
