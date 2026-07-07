package dev.itayp.tasker.planning.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Structured payload the assistant submits via the `submit_plan` tool. The backend persists
 * the human-readable [summary] into PlanningSessionEntity.summary and the structured [tasks]
 * (with time slots) into the planned_task / planned_task_slot tables.
 *
 * [message] is the user-facing farewell rendered to the channel once the plan is finalized.
 * Carrying it on the submission (rather than relying on a separate `say` call in the same turn)
 * guarantees the session always ends with an acknowledgement, even when the model calls
 * `submit_plan` on its own.
 *
 * [contextSuggestion] is an optional, durable fact the assistant learned this session and proposes
 * adding to the user's (user-authored) context block. When present, the orchestrator asks the user
 * to accept or reject it after finalizing — the plan itself is committed regardless of the answer.
 * Left blank/absent when there is nothing new worth remembering.
 */
data class AgreedPlan(
    val tasks: List<AgreedPlanTask>,
    val summary: String,
    val message: String? = null,
    @JsonProperty("context_suggestion") val contextSuggestion: String? = null,
)

data class AgreedPlanTask(
    @JsonProperty("task_id") val taskId: UUID,
    val title: String,
    val slots: List<AgreedTimeSlot>,
    val notes: String? = null,
)

data class AgreedTimeSlot(
    @JsonProperty("start_iso") val startIso: String,
    @JsonProperty("end_iso") val endIso: String,
    val label: String? = null,
)
