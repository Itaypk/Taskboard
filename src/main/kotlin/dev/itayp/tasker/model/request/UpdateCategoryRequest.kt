package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UpdateCategoryRequest(
    @field:NotBlank @field:Size(max = 64) val label: String,
    @field:NotBlank @field:Size(max = 32) val swatchId: String
)
