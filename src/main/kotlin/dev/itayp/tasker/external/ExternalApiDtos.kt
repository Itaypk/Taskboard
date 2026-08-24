package dev.itayp.tasker.external

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.BoardSummary
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * External view of a task. Deliberately richer than the SPA's `TaskResponse`, which omits
 * `boardId` and carries tags without their ids — both of which a caller needs in order to make a
 * follow-up write without a second round trip.
 *
 * Every value is a string or a primitive so a consumer needs no date/enum library.
 */
data class ExternalTaskResponse(
    val id: String,
    val boardId: String,
    val boardName: String,
    val title: String,
    val description: String?,
    val url: String?,
    val priority: String?,
    val deadline: String?,
    val estimatedMinutes: Int?,
    val status: String,
    val categoryId: String,
    val categoryLabel: String,
    val tags: List<ExternalTagResponse>,
    val assigneeUserId: String?,
    val relevantFrom: String?,
    val hiddenFromAssistant: Boolean,
    val createdAt: String,
    val updatedAt: String?,
)

data class ExternalTagResponse(
    val id: String,
    val label: String,
    val colorId: String,
)

data class ExternalCategoryResponse(
    val id: String,
    val boardId: String,
    val label: String,
    val colorId: String,
)

/**
 * Who the calling token acts as, and the context a caller needs to interpret dates correctly.
 *
 * [timeZone] is the load-bearing field: deadlines and `relevantFrom` are plain `YYYY-MM-DD`, and
 * the server hides future-dated tasks relative to the *user's* zone — so a caller resolving
 * "tomorrow" from its own clock will get it wrong for anyone not sitting in that zone.
 */
data class ExternalMeResponse(
    val userId: String,
    val displayName: String?,
    val timeZone: String,
    val preferredLanguage: String,
    val defaultBoardId: String?,
    /** What this token may do: `read` or `write`. Lets a caller check before attempting a write. */
    val tokenScope: String,
)

data class ExternalBoardResponse(
    val id: String,
    val name: String,
    val role: String,
    /** True for the board that write calls target when the request names none. */
    val isDefault: Boolean,
)

/** Wrapper so list responses can carry a count without the client re-deriving it. */
data class ExternalTaskListResponse(
    val tasks: List<ExternalTaskResponse>,
    val count: Int,
    /** True when [tasks] was truncated by `limit` — the caller should narrow its query. */
    val truncated: Boolean,
)

/**
 * Create a task. Only [title] is required: [boardId] falls back to the user's default board and
 * [categoryId] to that board's first category, so a minimal call is `{"title": "..."}`.
 *
 * [tags] are plain labels. An existing tag is matched case-insensitively; an unknown one is
 * created with an auto-assigned colour.
 */
data class ExternalCreateTaskRequest(
    @field:Size(max = 500) val title: String,
    val boardId: String? = null,
    val categoryId: String? = null,
    @field:Size(max = 5000) val description: String? = null,
    @field:Size(max = 2000) val url: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    val tags: List<String>? = null,
    val relevantFrom: String? = null,
    val hiddenFromAssistant: Boolean? = null,
)

/**
 * Partial update. **An omitted or null field is left unchanged** — unlike the SPA's
 * `PUT /api/v1/.../tasks/{id}`, which is a full replace and silently resets anything not sent.
 *
 * Because "absent" and "explicitly null" are indistinguishable here, unsetting a field is a
 * separate, explicit operation: name it in [clear] (e.g. `{"clear": ["deadline"]}`). That avoids
 * needing a tri-state JSON wrapper, and it makes destructive intent impossible to express by
 * accident.
 */
data class ExternalUpdateTaskRequest(
    @field:Size(max = 500) val title: String? = null,
    @field:Size(max = 5000) val description: String? = null,
    @field:Size(max = 2000) val url: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    val status: String? = null,
    val categoryId: String? = null,
    val tags: List<String>? = null,
    val relevantFrom: String? = null,
    val hiddenFromAssistant: Boolean? = null,
    /** Names of fields to unset. See [CLEARABLE_FIELDS] for what may appear here. */
    val clear: List<String>? = null,
) {
    companion object {
        val CLEARABLE_FIELDS = setOf(
            "description", "url", "priority", "deadline", "estimatedMinutes", "relevantFrom", "tags",
        )
    }
}

fun BacklogTask.toExternalResponse(boardName: String) = ExternalTaskResponse(
    id = id.toString(),
    boardId = boardId.toString(),
    boardName = boardName,
    title = title,
    description = description,
    url = url,
    priority = priority?.name?.lowercase(),
    deadline = deadline?.toString(),
    estimatedMinutes = estimatedMinutes,
    status = status.name.lowercase(),
    categoryId = category.id.toString(),
    categoryLabel = category.label,
    tags = tags.map { it.toExternalResponse() }.sortedBy { it.label.lowercase() },
    assigneeUserId = assigneeUserId?.toString(),
    relevantFrom = relevantFrom?.toString(),
    hiddenFromAssistant = hiddenFromAssistant,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt?.toString(),
)

fun BacklogTaskTag.toExternalResponse() = ExternalTagResponse(
    id = id.toString(),
    label = label,
    colorId = colorId.name.lowercase(),
)

fun BacklogTaskCategory.toExternalResponse() = ExternalCategoryResponse(
    id = id.toString(),
    boardId = boardId.toString(),
    label = label,
    colorId = swatchId.name.lowercase(),
)

fun BoardSummary.toExternalResponse(isDefault: Boolean) = ExternalBoardResponse(
    id = id.toString(),
    name = name,
    role = role.name.lowercase(),
    isDefault = isDefault,
)

/** Parses a UUID path/query value, returning null rather than throwing on malformed input. */
fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
