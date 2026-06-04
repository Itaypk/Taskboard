package dev.itayp.tasker.model.response

/**
 * Lightweight summary of a finalized weekly plan (no task bodies), used by the plan index that
 * powers the drawer's week navigation.
 */
data class PlanSummaryResponse(
    val id: String,
    val weekStart: String,
    val weekEnd: String,
    val status: String,
    val taskCount: Int,
    val hasSummary: Boolean,
)
