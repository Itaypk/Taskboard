package dev.itayp.tasker.model.request

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UpdateBacklogTaskRequest(
    @field:NotBlank(message = "Title is required.")
    @field:Size(max = 500, message = "Title must be at most 500 characters.")
    val title: String,
    @field:Size(max = 5000, message = "Description must be at most 5000 characters.")
    val description: String? = null,
    /**
     * No scheme constraint here: the rule depends on the stored value (see [TaskUrl]), so it is
     * checked in `BacklogTaskService.updateTask` — the same reasoning as [RecurrenceInput].
     */
    @field:Size(max = 2000, message = "Link must be at most 2000 characters.")
    val url: String? = null,
    @field:Size(max = 32) val priority: String? = null,
    @field:Size(max = 64)
    @field:Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "Deadline must be a valid date.")
    val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    @field:Size(max = 32) val status: String = "todo",
    @field:NotBlank(message = "A category is required.") @field:Size(max = 64) val categoryId: String,
    @field:Size(max = 32) val tags: List<TagInput> = emptyList(),
    @field:Size(max = 64)
    @field:Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "Available-from must be a valid date.")
    val relevantFrom: String? = null,
    /** Opt the task out of the AI assistant's context (planner slate, backlog search, quick-add sampling). */
    val hiddenFromAssistant: Boolean = false,
    /**
     * Null = not recurring. This is a full replace like every other field: a caller rebuilding a
     * request from a task must carry the current rule forward or recurrence is silently switched off.
     */
    @field:Valid val recurrence: RecurrenceInput? = null,
)
