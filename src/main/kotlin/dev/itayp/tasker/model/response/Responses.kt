package dev.itayp.tasker.model.response

import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag

data class LanguageOption(val code: String, val label: String)

data class SettingsOptionsResponse(
    val timeZones: List<String>,
    val languages: List<LanguageOption>,
)

data class UserSettingsResponse(
    val displayName: String?,
    val contextBlock: String?,
    val timeZone: String,
    val preferredLanguage: String,
)

fun UserSettingsEntity.toResponse() = UserSettingsResponse(
    displayName = displayName,
    contextBlock = contextBlock,
    timeZone = timeZone,
    preferredLanguage = preferredLanguage,
)

data class TagResponseItem(val label: String, val colorId: String)

data class TaskResponse(
    val id: String,
    val title: String,
    val description: String?,
    val url: String?,
    val priority: String?,
    val deadline: String?,
    val estimatedMinutes: Int?,
    val status: String,
    val categoryId: String,
    val tags: List<TagResponseItem>,
    val createdAt: String,
    val updatedAt: String?
)

data class CategoryResponse(val id: String, val label: String, val swatchId: String)

data class TagResponse(val id: String, val label: String, val colorId: String)

fun BacklogTask.toResponse() = TaskResponse(
    id = id.toString(),
    title = title,
    description = description,
    url = url,
    priority = priority?.name?.lowercase(),
    deadline = deadline?.toString(),
    estimatedMinutes = estimatedMinutes,
    status = status.name.lowercase(),
    categoryId = category.id.toString(),
    tags = tags.map { TagResponseItem(it.label, it.colorId.name.lowercase()) },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt?.toString()
)

fun BacklogTaskCategory.toResponse() = CategoryResponse(
    id = id.toString(),
    label = label,
    swatchId = swatchId.name.lowercase()
)

fun BacklogTaskTag.toResponse() = TagResponse(
    id = id.toString(),
    label = label,
    colorId = colorId.name.lowercase()
)
