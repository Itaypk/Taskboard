package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateBoardRequest(
    @field:NotBlank @field:Size(max = 60) val name: String,
)

data class UpdateBoardRequest(
    @field:NotBlank @field:Size(max = 60) val name: String,
)
