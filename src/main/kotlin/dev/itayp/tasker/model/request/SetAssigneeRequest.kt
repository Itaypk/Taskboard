package dev.itayp.tasker.model.request

import jakarta.validation.constraints.Size

/**
 * Sets (or clears) a task's assignee. `userId == null` unassigns; a non-null value must name a
 * current member of the board (Decision 7 — open coordination, any member may set any member).
 */
data class SetAssigneeRequest(
    @field:Size(max = 64) val userId: String? = null,
)
