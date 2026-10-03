package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal fun digestTask(
    title: String,
    id: UUID = UUID.randomUUID(),
    deadline: LocalDate? = null,
    status: TaskStatus = TaskStatus.TODO,
    priority: TaskPriority? = null,
    assigneeUserId: UUID? = null,
    tutorial: Boolean = false,
): BacklogTask {
    val boardId = UUID.randomUUID()
    return BacklogTask(
        id = id,
        boardId = boardId,
        assigneeUserId = assigneeUserId,
        title = title,
        description = null,
        url = null,
        priority = priority,
        deadline = deadline,
        estimatedMinutes = null,
        status = status,
        category = BacklogTaskCategory(UUID.randomUUID(), boardId, "General", CategoryColor.SKY),
        tags = emptySet(),
        sortKey = "a",
        createdAt = Instant.parse("2026-09-01T08:00:00Z"),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
        tutorial = tutorial,
    )
}
