package dev.itayp.tasker.jpa

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag

fun BacklogTaskEntity.toDomain(crypto: UserCryptoService): BacklogTask {
    val ownerId = userId ?: throw IllegalStateException("BacklogTaskEntity must have userId")
    return BacklogTask(
        id = id ?: throw IllegalStateException("BacklogTaskEntity must have an id"),
        userId = ownerId,
        title = crypto.decrypt(ownerId, title) ?: throw IllegalStateException("BacklogTaskEntity must have title"),
        description = crypto.decrypt(ownerId, description),
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
        relevantFrom = relevantFrom
    )
}

fun BacklogTaskCategoryEntity.toDomain(): BacklogTaskCategory =
    BacklogTaskCategory(
        id = id ?: throw IllegalStateException("BacklogTaskCategoryEntity must have an id"),
        userId = userId ?: throw IllegalStateException("BacklogTaskCategoryEntity must have userId"),
        label = label ?: throw IllegalStateException("BacklogTaskCategoryEntity must have label"),
        swatchId = swatchId ?: throw IllegalStateException("BacklogTaskCategoryEntity must have swatchId")
    )

fun BacklogTaskTagEntity.toDomain(): BacklogTaskTag =
    BacklogTaskTag(
        id = id ?: throw IllegalStateException("BacklogTaskTagEntity must have an id"),
        userId = userId ?: throw IllegalStateException("BacklogTaskTagEntity must have userId"),
        label = label ?: throw IllegalStateException("BacklogTaskTagEntity must have label"),
        colorId = colorId ?: throw IllegalStateException("BacklogTaskTagEntity must have colorId"),
        description = description
    )
