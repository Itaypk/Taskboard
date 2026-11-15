package dev.itayp.tasker.planning.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Structured payload the assistant submits via the `submit_plan` tool. The backend persists the
 * structured [tasks] (with time slots) into the planned_task / planned_task_slot tables; the
 * schedule is always rendered from there, never from model-written prose.
 *
 * [weekNote] (first finalization only) and [revisionNote] (revise sessions only) carry what the
 * assistant learned that the schedule can't say: capacity, why something was deferred, one-off
 * constraints. Both are optional. The week note is stored once; a revision note is appended next to
 * it, so a revision never rewrites earlier context (GitHub issue #229). The backend ignores whichever
 * of the two doesn't apply to the session.
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
    @JsonProperty("week_note") val weekNote: String? = null,
    @JsonProperty("revision_note") val revisionNote: String? = null,
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
