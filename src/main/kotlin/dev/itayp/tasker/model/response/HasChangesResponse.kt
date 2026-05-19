package dev.itayp.tasker.model.response

data class HasChangesResponse(
    val hasChanges: Boolean,
    val checkedAt: String,
)
