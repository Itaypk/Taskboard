package dev.itayp.tasker.model.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class InviteMemberRequest(
    @field:NotBlank @field:Email val email: String,
)

data class SetMemberRoleRequest(
    @field:NotBlank val role: String,
)
