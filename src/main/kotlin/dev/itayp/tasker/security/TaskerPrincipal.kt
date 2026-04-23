package dev.itayp.tasker.security

import java.util.UUID

data class TaskerPrincipal(val userId: UUID) {
    override fun toString(): String = userId.toString()
}
