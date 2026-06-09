package dev.itayp.tasker.model.response

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/**
 * Round-trippable account snapshot used by both `GET /account/export` and
 * `POST /account/import`. Spec lives in `docs/export-format-v2.md`; bump
 * [formatVersion] when the schema changes.
 */
data class AccountExportResponse(
    @field:Positive
    val formatVersion: Int = 2,
    val exportedAt: String,
    @field:Valid
    val user: UserExport,
    @field:Valid
    val settings: SettingsExport?,
    @field:Valid
    val boards: List<BoardExport>,
)

data class BoardExport(
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    @field:NotBlank @field:Size(max = 50)
    val role: String,
    @field:Valid
    val categories: List<CategoryExport>,
    @field:Valid
    val tags: List<TagExport>,
    @field:Valid
    val tasks: List<TaskExport>,
)

data class UserExport(
    val id: String,
    @field:Size(max = 255)
    val telegramUsername: String?,
    @field:Size(max = 255)
    val telegramFirstName: String?,
    val email: String?,
    @field:Size(max = 320)
    val createdAt: String?,
)

data class SettingsExport(
    @field:Size(max = 255)
    val displayName: String?,
    @field:Size(max = 4096)
    val contextBlock: String?,
    @field:NotBlank @field:Size(max = 100)
    val timeZone: String,
    @field:NotBlank @field:Size(max = 10)
    val preferredLanguage: String,
    val calendarInviteEmail: Boolean = false,
    @field:Size(max = 20)
    val gender: String?,
    @field:Size(max = 10_000)
    val agentDescription: String?,
    @field:Size(max = 64)
    val planningCron: String?,
    @field:Size(max = 10)
    val weekStartDay: String?,
    val autoArchiveDays: Int?,
)

data class CategoryExport(
    val id: String,
    @field:NotBlank @field:Size(max = 255)
    val label: String,
    @field:NotBlank @field:Size(max = 50)
    val swatchId: String,
)

data class TagExport(
    val id: String,
    @field:NotBlank @field:Size(max = 255)
    val label: String,
    @field:NotBlank @field:Size(max = 50)
    val colorId: String,
    @field:Size(max = 1000)
    val description: String?,
)

data class TaskExport(
    val id: String,
    @field:NotBlank @field:Size(max = 500)
    val title: String,
    @field:Size(max = 10_000)
    val description: String?,
    @field:Size(max = 2000) @field:Pattern(regexp = "^$|^https?://.*")
    val url: String?,
    @field:Size(max = 32)
    val priority: String?,
    @field:Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$")
    val deadline: String?,
    val estimatedMinutes: Int?,
    @field:NotBlank @field:Size(max = 32)
    val status: String,
    @field:NotBlank
    val categoryId: String,
    val tagIds: List<String>,
    @field:NotBlank @field:Size(max = 255)
    val sortKey: String,
    @field:NotBlank
    val createdAt: String,
    val updatedAt: String?,
    @field:Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$")
    val relevantFrom: String?,
    val assignee: String? = null,
)
