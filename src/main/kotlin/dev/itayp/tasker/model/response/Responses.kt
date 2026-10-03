package dev.itayp.tasker.model.response

import dev.itayp.tasker.ai.access.AiTier
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.model.TagUsage
import dev.itayp.tasker.model.TaskRecurrence
import dev.itayp.tasker.model.UserSettings

data class LanguageOption(val code: String, val label: String)

data class GenderOption(val code: String, val label: String)

data class SettingsOptionsResponse(
    val timeZones: List<String>,
    val languages: List<LanguageOption>,
    val genders: List<GenderOption>,
)

data class UserSettingsResponse(
    val displayName: String?,
    val contextBlock: String?,
    val timeZone: String,
    val preferredLanguage: String,
    val calendarInviteEmail: Boolean,
    val appReminders: Boolean,
    val gender: String?,
    val agentDescription: String?,
    val planningCron: String?,
    val weekStartDay: String?,
    val autoArchiveDays: Int?,
    val aiEnabled: Boolean,
    val aiEnhancedReminders: Boolean,
    val aiTier: String,
    val aiTierGrantsAccess: Boolean,
    val dailyDigestEnabled: Boolean,
    val dailyDigestDueTasks: Boolean,
    val dailyDigestCron: String,
    val email: String?,
    val emailVerified: Boolean,
)

fun UserSettings.toResponse(email: String?, emailVerified: Boolean) = UserSettingsResponse(
    displayName = displayName,
    contextBlock = contextBlock,
    timeZone = timeZone,
    preferredLanguage = preferredLanguage,
    calendarInviteEmail = calendarInviteEmail,
    appReminders = appReminders,
    gender = gender,
    agentDescription = agentDescription,
    planningCron = planningCron,
    weekStartDay = weekStartDay,
    autoArchiveDays = autoArchiveDays,
    aiEnabled = aiEnabled,
    aiEnhancedReminders = aiEnhancedReminders,
    aiTier = aiTier,
    aiTierGrantsAccess = AiTier.fromName(aiTier).grantsAccess,
    dailyDigestEnabled = dailyDigestEnabled,
    dailyDigestDueTasks = dailyDigestDueTasks,
    dailyDigestCron = dailyDigestCron,
    email = email,
    emailVerified = emailVerified,
)

data class BoardResponse(
    val id: String,
    val name: String,
    val role: String,
    val createdAt: String,
    val memberCount: Int,
    val mascot: String,
)

fun BoardSummary.toResponse() = BoardResponse(
    id = id.toString(),
    name = name,
    role = role.name,
    createdAt = createdAt.toString(),
    memberCount = memberCount,
    mascot = mascot,
)

data class BoardMemberResponse(
    val userId: String,
    val role: String,
    val joinedAt: String,
    val displayName: String,
)

fun dev.itayp.tasker.model.BoardMember.toResponse() = BoardMemberResponse(
    userId = userId.toString(),
    role = role.name,
    joinedAt = joinedAt.toString(),
    displayName = displayName,
)

/** Address-free pending invitation (we never store the recipient's plaintext email). */
data class PendingInvitationResponse(
    val id: String,
    val createdAt: String,
    val expiresAt: String,
)

fun dev.itayp.tasker.model.PendingInvitation.toResponse() = PendingInvitationResponse(
    id = id.toString(),
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString(),
)

data class InvitationPreviewResponse(
    val boardName: String,
    val inviterName: String,
    val expiresAt: String,
)

fun dev.itayp.tasker.model.InvitationPreview.toResponse() = InvitationPreviewResponse(
    boardName = boardName,
    inviterName = inviterName,
    expiresAt = expiresAt.toString(),
)

data class AcceptInvitationResponse(
    val boardId: String,
    val boardName: String,
)

fun dev.itayp.tasker.model.AcceptedInvitation.toResponse() = AcceptInvitationResponse(
    boardId = boardId.toString(),
    boardName = boardName,
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
    val sortKey: String,
    val createdAt: String,
    val updatedAt: String?,
    val lastScheduledInSessionId: String?,
    val relevantFrom: String?,
    /** Member who has claimed/been assigned this task; null = unassigned. Names resolved client-side via the members endpoint. */
    val assigneeUserId: String?,
    /** True for seeded tutorial tasks: the client hides edit affordances and offers a one-click "clear tutorial". */
    val tutorial: Boolean,
    /** True when the user has excluded this task from the AI assistant's context. */
    val hiddenFromAssistant: Boolean,
    val recurrence: RecurrenceResponse?,
    /** Read-only. Latest completion date of a recurring original, or the completion date of a copy. */
    val lastCompletedOn: String?,
    /** Read-only. Non-null marks a completed-occurrence copy, pointing at its recurring original. */
    val recurrenceSourceId: String?,
)

data class RecurrenceResponse(
    val kind: String,
    val every: Int?,
    val day: Int?,
    val month: Int?,
    val dueWithinDays: Int?,
)

fun TaskRecurrence.toResponse() = RecurrenceResponse(kind.name, every, day, month, dueWithinDays)

data class TimeSlotResponse(
    val startIso: String,
    val endIso: String,
    val label: String?,
)

data class PlanTaskResponse(
    val task: TaskResponse,
    val slots: List<TimeSlotResponse>,
    val notes: String?,
)

/**
 * A one-off calendar event captured through /add. Surfaced in the weekly plan drawer so the user
 * can see what they've already locked into the week — independent of whether a plan has been
 * finalized for that week yet.
 */
data class OneOffEventResponse(
    val id: String,
    val title: String,
    /** ISO instant. */
    val startsAt: String,
    /** ISO instant. */
    val endsAt: String,
    val location: String?,
    val notes: String?,
)

data class CategoryResponse(val id: String, val label: String, val swatchId: String)

data class TagResponse(val id: String, val label: String, val colorId: String, val usageCount: Int)

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
    sortKey = sortKey,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt?.toString(),
    lastScheduledInSessionId = lastScheduledInSessionId?.toString(),
    relevantFrom = relevantFrom?.toString(),
    assigneeUserId = assigneeUserId?.toString(),
    tutorial = tutorial,
    hiddenFromAssistant = hiddenFromAssistant,
    recurrence = recurrence?.toResponse(),
    lastCompletedOn = lastCompletedOn?.toString(),
    recurrenceSourceId = recurrenceSourceId?.toString(),
)

fun BacklogTaskCategory.toResponse() = CategoryResponse(
    id = id.toString(),
    label = label,
    swatchId = swatchId.name.lowercase()
)

fun TagUsage.toResponse() = TagResponse(
    id = tag.id.toString(),
    label = tag.label,
    colorId = tag.colorId.name.lowercase(),
    usageCount = usageCount,
)
