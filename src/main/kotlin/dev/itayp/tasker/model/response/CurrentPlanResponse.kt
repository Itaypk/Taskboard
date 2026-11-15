package dev.itayp.tasker.model.response

data class CurrentPlanResponse(
    val id: String,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    /** What the assistant noted about the week when it was planned; null when it noted nothing. */
    val weekNote: String?,
    /** One entry per revise session that left a note, oldest first. */
    val revisionNotes: List<RevisionNoteResponse>,
    val tasks: List<PlanTaskResponse>,
    val weekStart: String,
    val weekEnd: String,
)

data class RevisionNoteResponse(
    val at: String,
    val text: String,
)
