package dev.itayp.tasker.model.response

data class CurrentPlanResponse(
    val id: String,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    val summary: String?,
    val tasks: List<PlanTaskResponse>,
    val weekStart: String,
    val weekEnd: String,
)
