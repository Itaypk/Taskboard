package dev.itayp.tasker.external

/**
 * One planning week as the external API shows it: the finalized plan (null when the week was never
 * planned) and the one-off events in it, plus the context a caller needs to write slots — the
 * week's bounds and the user's time zone — and where agreed blocks end up (`calendarDelivery`).
 */
data class ExternalWeekPlanResponse(
    val weekStart: String,
    /** The week's last day, inclusive. */
    val weekEnd: String,
    val timeZone: String,
    /** `email` (calendar invitations), `feed` (calendar subscription) or `none`: whether plan changes reach a calendar. */
    val calendarDelivery: String,
    val plan: ExternalPlanResponse?,
    val events: List<ExternalEventResponse>,
)

data class ExternalPlanResponse(
    val id: String,
    val finalizedAt: String?,
    val weekNote: String?,
    val revisionNotes: List<ExternalRevisionNoteResponse>,
    val tasks: List<ExternalPlanTaskResponse>,
    /**
     * Planned tasks left out because they're hidden from assistants (`includeHidden=true` shows
     * them). A submit keeps them as they are unless it lists them.
     */
    val hiddenTasksOmitted: Int,
)

data class ExternalRevisionNoteResponse(val at: String, val text: String)

data class ExternalPlanTaskResponse(
    val task: ExternalTaskResponse,
    val slots: List<ExternalSlotResponse>,
    val notes: String?,
)

data class ExternalSlotResponse(val start: String, val end: String, val label: String?)

data class ExternalEventResponse(
    val id: String,
    val title: String,
    val start: String,
    val end: String,
    val location: String?,
    val notes: String?,
)

/**
 * A whole plan for one week. It replaces the week's plan: a task left out comes off the plan
 * (its calendar invitations are cancelled), except assistant-hidden tasks, which stay unless listed.
 * Validated in the controller rather than with bean validation so every error can say what to fix.
 */
data class ExternalSubmitPlanRequest(
    val tasks: List<ExternalPlanTaskInput>? = null,
    /** Week note on a week's first plan; appended as a revision note on later submits. */
    val note: String? = null,
)

data class ExternalPlanTaskInput(
    val taskId: String? = null,
    val slots: List<ExternalSlotInput>? = null,
    val notes: String? = null,
)

data class ExternalSlotInput(
    val start: String? = null,
    val end: String? = null,
    val label: String? = null,
)
