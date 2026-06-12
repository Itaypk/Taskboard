package dev.itayp.tasker.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class TaskPriority {
    LOW, MEDIUM, HIGH;

    companion object {
        /**
         * The lowercase value set accepted wherever a priority travels as a string (API requests,
         * AI tool schemas/validation), kept in declaration order.
         */
        val allowedValues: Set<String> = entries.mapTo(LinkedHashSet()) { it.name.lowercase() }
    }
}

enum class TaskStatus {
    TODO, DONE, ARCHIVED
}

enum class TagColor {
    SAGE, SKY, CORAL, AMBER, VIOLET, LEMON, TEAL, ROSE
}

enum class CategoryColor {
    SUNSHINE, BLOSSOM, MINT, SKY, LILAC, PEACH, CREAM, SAGE, ROSE, SAND
}

data class BacklogTaskTag(
    val id: UUID,
    val boardId: UUID,
    val label: String,
    val colorId: TagColor,
    val description: String?
)

data class BacklogTaskCategory(
    val id: UUID,
    val boardId: UUID,
    val label: String,
    val swatchId: CategoryColor
)

data class BacklogTask(
    val id: UUID,
    val boardId: UUID,
    val assigneeUserId: UUID?,
    val title: String,
    val description: String?,
    val url: String?,
    val priority: TaskPriority?,
    val deadline: LocalDate?,
    val estimatedMinutes: Int?,
    val status: TaskStatus,
    val category: BacklogTaskCategory,
    val tags: Set<BacklogTaskTag>,
    val sortKey: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val rescheduleCount: Int,
    val lastScheduledInSessionId: UUID?,
    val relevantFrom: LocalDate?
)
