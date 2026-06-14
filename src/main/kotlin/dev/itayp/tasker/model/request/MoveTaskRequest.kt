package dev.itayp.tasker.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Body for moving a task to another board: the destination board and the category to land it in. */
data class MoveTaskRequest(
    @field:NotBlank(message = "A destination board is required.")
    @field:Size(max = 64)
    val targetBoardId: String,
    @field:NotBlank(message = "A destination category is required.")
    @field:Size(max = 64)
    val categoryId: String,
)
