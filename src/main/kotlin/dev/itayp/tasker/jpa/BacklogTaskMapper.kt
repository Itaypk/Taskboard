package dev.itayp.tasker.jpa

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag

fun BacklogTaskEntity.toDomain(crypto: BoardCryptoService): BacklogTask {
    val board = boardId ?: throw IllegalStateException("BacklogTaskEntity must have boardId")
    return BacklogTask(
        id = id ?: throw IllegalStateException("BacklogTaskEntity must have an id"),
        boardId = board,
        assigneeUserId = assigneeUserId,
        title = crypto.decrypt(board, title) ?: throw IllegalStateException("BacklogTaskEntity must have title"),
        description = crypto.decrypt(board, description),
        url = url,
        priority = priority,
        deadline = deadline,
        estimatedMinutes = estimatedMinutes,
        status = status ?: throw IllegalStateException("BacklogTaskEntity must have status"),
        category = category?.toDomain() ?: throw IllegalStateException("BacklogTaskEntity must have category"),
        tags = tags.map { it.toDomain() }.toSet(),
        sortKey = sortKey ?: throw IllegalStateException("BacklogTaskEntity must have sortKey"),
        createdAt = createdAt ?: throw IllegalStateException("BacklogTaskEntity must have createdAt"),
        updatedAt = updatedAt,
        rescheduleCount = rescheduleCount,
        lastScheduledInSessionId = lastScheduledInSessionId,
        relevantFrom = relevantFrom,
        tutorial = tutorial
    )
}

fun BacklogTaskCategoryEntity.toDomain(): BacklogTaskCategory =
    BacklogTaskCategory(
        id = id ?: throw IllegalStateException("BacklogTaskCategoryEntity must have an id"),
        boardId = boardId ?: throw IllegalStateException("BacklogTaskCategoryEntity must have boardId"),
        label = label ?: throw IllegalStateException("BacklogTaskCategoryEntity must have label"),
        swatchId = swatchId ?: throw IllegalStateException("BacklogTaskCategoryEntity must have swatchId")
    )

fun BacklogTaskTagEntity.toDomain(): BacklogTaskTag =
    BacklogTaskTag(
        id = id ?: throw IllegalStateException("BacklogTaskTagEntity must have an id"),
        boardId = boardId ?: throw IllegalStateException("BacklogTaskTagEntity must have boardId"),
        label = label ?: throw IllegalStateException("BacklogTaskTagEntity must have label"),
        colorId = colorId ?: throw IllegalStateException("BacklogTaskTagEntity must have colorId"),
        description = description
    )
