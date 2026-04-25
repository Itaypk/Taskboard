package dev.itayp.tasker.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class TaskPriority {
    LOW, MEDIUM, HIGH
}

enum class TaskStatus {
    TODO, DONE
}

enum class TagColor {
    SAGE, SKY, CORAL, AMBER, VIOLET, LEMON, TEAL, ROSE
}

enum class CategoryColor {
    SUNSHINE, BLOSSOM, MINT, SKY, LILAC, PEACH, CREAM, SAGE, ROSE, SAND
}

data class BacklogTaskTag(
    val id: UUID,
    val userId: UUID,
    val label: String,
    val colorId: TagColor,
    val description: String?
)

data class BacklogTaskCategory(
    val id: UUID,
    val userId: UUID,
    val label: String,
    val swatchId: CategoryColor
)

data class BacklogTask(
    val id: UUID,
    val userId: UUID,
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
    val updatedAt: Instant?
)
