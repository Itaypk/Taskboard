package dev.itayp.tasker.security

import java.io.Serializable
import java.util.UUID

data class TaskerPrincipal(val userId: UUID) : Serializable {
    override fun toString(): String = userId.toString()
}
