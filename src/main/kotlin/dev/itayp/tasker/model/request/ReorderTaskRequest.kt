package dev.itayp.tasker.model.request

/**
 * Request body for reordering a task.
 *
 * The caller describes the desired position by naming the tasks
 * immediately before and after the target slot:
 *
 * - [afterId]  = null → move to the very beginning of the list
 * - [beforeId] = null → move to the very end of the list
 * - both non-null → place between those two tasks
 *
 * The server computes the new sort key and returns the updated task.
 */
data class ReorderTaskRequest(
    val afterId: String?,
    val beforeId: String?,
)
