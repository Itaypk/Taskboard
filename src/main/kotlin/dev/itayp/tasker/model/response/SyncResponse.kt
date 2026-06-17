package dev.itayp.tasker.model.response

/**
 * One-shot staleness snapshot for an open board tab. Each `*ChangedAt` is the watermark for that
 * entity type (ISO-8601 instant, or null if nothing has been recorded yet); the client keeps the
 * last-seen values and refetches only the types whose timestamp moved. [appVersion] lets the client
 * notice a backend redeploy and prompt a page refresh.
 */
data class SyncResponse(
    val checkedAt: String,
    val tasksChangedAt: String?,
    val tagsChangedAt: String?,
    val categoriesChangedAt: String?,
    val planChangedAt: String?,
    val appVersion: String,
)
