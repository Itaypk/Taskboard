package dev.itayp.tasker.model

import java.time.Duration
import java.time.Instant

/**
 * A lightweight, on-demand snapshot of a user's activity, surfaced through the Telegram
 * `/stats` command (and, later, the web UI). Purely informational — not a gamification
 * mechanic. All per-week and completion-time figures are derived from the append-only
 * backlog change-event log, so they reflect recorded activity since logging began.
 */
data class UserStats(
    val joinedAt: Instant?,
    val openTasks: Long,
    val completedTasks: Long,
    val planningSessions: Long,
    val avgTasksCreatedPerWeek: Double,
    val avgTasksCompletedPerWeek: Double,
    /** Mean time from task creation to its first completion. Null when nothing has been completed. */
    val avgCompletion: Duration?,
) {
    /** True for a brand-new user with no tasks and no recorded activity — render an encouraging empty state instead. */
    val isEmpty: Boolean
        get() = openTasks == 0L && completedTasks == 0L && planningSessions == 0L && avgTasksCreatedPerWeek == 0.0
}
