package dev.itayp.tasker.model.request

import dev.itayp.tasker.model.RecurrenceKind
import dev.itayp.tasker.model.TaskRecurrence
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class TagInput(
    @field:Size(max = 64) val id: String? = null,
    @field:NotBlank @field:Size(max = 64) val label: String,
    @field:NotBlank @field:Size(max = 32) val colorId: String
)

/**
 * A recurrence rule as it travels on the wire. Cross-field rules (which fields each kind takes) are
 * checked in the service via [dev.itayp.tasker.model.TaskRecurrence.validationError], since bean
 * validation can't express them.
 */
data class RecurrenceInput(
    @field:NotBlank @field:Size(max = 16) val kind: String,
    val every: Int? = null,
    val day: Int? = null,
    val month: Int? = null,
    val dueWithinDays: Int? = null,
)

fun TaskRecurrence.toInput() = RecurrenceInput(kind.name, every, day, month, dueWithinDays)

/** Null when valid; otherwise a sentence naming the problem and the allowed values. */
fun RecurrenceInput.validationError(): String? {
    val parsed = RecurrenceKind.parse(kind)
        ?: return "Unknown recurrence kind '$kind'. Allowed: ${RecurrenceKind.allowedValues.joinToString()}."
    return TaskRecurrence(parsed, every, day, month, dueWithinDays).validationError()
}

/** Only call on an input that passed [validationError]. */
fun RecurrenceInput.toRule() = TaskRecurrence(RecurrenceKind.parse(kind)!!, every, day, month, dueWithinDays)

data class CreateBacklogTaskRequest(
    @field:NotBlank(message = "Title is required.")
    @field:Size(max = 500, message = "Title must be at most 500 characters.")
    val title: String,
    @field:Size(max = 5000, message = "Description must be at most 5000 characters.")
    val description: String? = null,
    @field:Size(max = 2000, message = "Link must be at most 2000 characters.")
    @field:Pattern(regexp = "^$|^https?://.*", message = "Link must start with http:// or https://")
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
    /** Null = not recurring. When set, `deadline` is derived from `relevantFrom` + `dueWithinDays`. */
    @field:Valid val recurrence: RecurrenceInput? = null,
)
