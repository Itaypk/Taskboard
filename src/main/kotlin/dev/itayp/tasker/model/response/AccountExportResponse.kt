package dev.itayp.tasker.model.response

data class AccountExportResponse(
    val exportedAt: String,
    val user: UserExport,
    val settings: SettingsExport?,
    val categories: List<CategoryExport>,
    val tags: List<TagExport>,
    val tasks: List<TaskExport>,
)

data class UserExport(
    val id: String,
    val telegramUsername: String?,
    val telegramFirstName: String?,
    val createdAt: String?,
)

data class SettingsExport(
    val displayName: String?,
    val contextBlock: String?,
    val timeZone: String,
    val preferredLanguage: String,
)

data class CategoryExport(
    val id: String,
    val label: String,
    val swatchId: String,
)

data class TagExport(
    val id: String,
    val label: String,
    val colorId: String,
    val description: String?,
)

data class TaskExport(
    val id: String,
    val title: String,
    val description: String?,
    val url: String?,
    val priority: String?,
    val deadline: String?,
    val estimatedMinutes: Int?,
    val status: String,
    val categoryId: String,
    val tagIds: List<String>,
    val sortKey: String,
    val createdAt: String,
    val updatedAt: String?,
)
