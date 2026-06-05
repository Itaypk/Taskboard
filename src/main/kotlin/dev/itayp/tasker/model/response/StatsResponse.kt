package dev.itayp.tasker.model.response

import dev.itayp.tasker.model.UserStats

/**
 * Web-facing view of a user's [UserStats]. Durations/instants are sent as primitives (ISO instant,
 * seconds) so the English-only web UI formats them itself, mirroring what [dev.itayp.tasker.service.StatsFormatter]
 * does for text channels.
 */
data class StatsResponse(
    val joinedAt: String?,
    val openTasks: Long,
    val completedTasks: Long,
    val planningSessions: Long,
    val avgTasksCreatedPerWeek: Double,
    val avgTasksCompletedPerWeek: Double,
    val avgCompletionSeconds: Long?,
)

fun UserStats.toResponse() = StatsResponse(
    joinedAt = joinedAt?.toString(),
    openTasks = openTasks,
    completedTasks = completedTasks,
    planningSessions = planningSessions,
    avgTasksCreatedPerWeek = avgTasksCreatedPerWeek,
    avgTasksCompletedPerWeek = avgTasksCompletedPerWeek,
    avgCompletionSeconds = avgCompletion?.seconds,
)
