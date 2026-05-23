package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UpdateBacklogTaskRequest(
    @field:NotBlank @field:Size(max = 500) val title: String,
    @field:Size(max = 5000) val description: String? = null,
    @field:Size(max = 2000) @field:Pattern(regexp = "^$|^https?://.*") val url: String? = null,
    @field:Size(max = 32) val priority: String? = null,
    @field:Size(max = 64) @field:Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$") val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    @field:Size(max = 32) val status: String = "todo",
    @field:NotBlank @field:Size(max = 64) val categoryId: String,
    @field:Size(max = 32) val tags: List<TagInput> = emptyList(),
    @field:Size(max = 64) @field:Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$") val relevantFrom: String? = null,
)
