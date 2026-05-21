package dev.itayp.tasker.model.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UpdateEmailRequest(
    @field:NotBlank
    @field:Size(max = 320)
    @field:Email
    @field:Pattern(regexp = "^[^\\r\\n]+$", message = "Email must not contain newlines")
    val email: String,
)
