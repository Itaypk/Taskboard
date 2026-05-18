package dev.itayp.tasker.planning.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Structured payload the assistant submits via the `submit_plan` tool. The backend turns
 * it into calendar events / mail / whatever the user has wired up. v1 only persists the
 * human-readable [summary] into PlanningSessionEntity.summary; the structured tasks live
 * in the conversation transcript and are available for inspection / future pickup.
 */
data class AgreedPlan(
    val tasks: List<AgreedPlanTask>,
    val summary: String,
)

data class AgreedPlanTask(
    @JsonProperty("task_id") val taskId: UUID?,
    val title: String,
    val slots: List<AgreedTimeSlot>,
    val notes: String? = null,
)

data class AgreedTimeSlot(
    @JsonProperty("start_iso") val startIso: String,
    @JsonProperty("end_iso") val endIso: String,
    val label: String? = null,
)
