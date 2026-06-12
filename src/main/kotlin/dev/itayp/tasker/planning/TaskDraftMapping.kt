package dev.itayp.tasker.planning

import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.model.request.TagInput

/**
 * Maps an approved [TaskDraft] to the [CreateBacklogTaskRequest] that persists it, resolving each
 * tag's colour through [TagColorOptions]. Shared by the in-session `create_task` tool and the
 * Telegram quick-add flow so the draft → persisted-task translation lives in exactly one place.
 *
 * [categoryId] must be non-null by this point — both callers resolve/validate it first — so a null
 * here is a programming error, surfaced loudly rather than silently dropped.
 */
fun TaskDraft.toCreateBacklogTaskRequest(): CreateBacklogTaskRequest =
    CreateBacklogTaskRequest(
        title = title,
        description = description,
        priority = priority,
        deadline = deadline,
        estimatedMinutes = estimatedMinutes,
        categoryId = requireNotNull(categoryId) { "TaskDraft.categoryId must be resolved before persisting" },
        tags = tags.map { TagInput(id = it.id, label = it.label, colorId = TagColorOptions.resolve(it.colorId)) },
    )
