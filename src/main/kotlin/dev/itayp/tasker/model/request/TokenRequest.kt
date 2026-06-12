package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank

data class TokenRequest(@field:NotBlank val token: String)
