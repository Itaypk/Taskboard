package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class TagInput(
    @field:Size(max = 64) val id: String? = null,
    @field:NotBlank @field:Size(max = 64) val label: String,
    @field:NotBlank @field:Size(max = 32) val colorId: String
)

data class CreateBacklogTaskRequest(
    @field:NotBlank @field:Size(max = 500) val title: String,
    @field:Size(max = 5000) val description: String? = null,
    @field:Size(max = 2000) @field:Pattern(regexp = "^$|^https?://.*") val url: String? = null,
    @field:Size(max = 32) val priority: String? = null,
    @field:Size(max = 64) val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    @field:Size(max = 32) val status: String = "todo",
    @field:NotBlank @field:Size(max = 64) val categoryId: String,
    @field:Size(max = 32) val tags: List<TagInput> = emptyList(),
    @field:Size(max = 64) val relevantFrom: String? = null,
)
