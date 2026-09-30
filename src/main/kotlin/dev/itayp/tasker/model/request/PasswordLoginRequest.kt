package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PasswordLoginRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val username: String,
    // bcrypt only reads the first 72 bytes; the cap just stops a huge body reaching the hasher.
    @field:NotBlank
    @field:Size(max = 256)
    val password: String,
)
