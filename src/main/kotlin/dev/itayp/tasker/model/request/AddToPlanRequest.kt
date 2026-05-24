package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AddToPlanRequest(
    @field:NotBlank @field:Size(max = 64) val startIso: String,
    @field:NotBlank @field:Size(max = 64) val endIso: String,
)
