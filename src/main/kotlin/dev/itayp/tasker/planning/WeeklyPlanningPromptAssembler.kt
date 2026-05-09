package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Assembles the weekly planning system prompt. Order mirrors `docs/MEMORY-MODEL.md`:
 * stable user context → previous session summary → diff → backlog candidates → calendar →
 * capacity → environment.
 */
@Service
class WeeklyPlanningPromptAssembler(
    private val templateLoader: PromptTemplateLoader,
    private val userSettingsService: UserSettingsService,
    private val plannerTaskSelector: PlannerTaskSelector,
    private val planningSessionService: PlanningSessionService,
    private val calendarWindowProvider: CalendarWindowProvider,
    private val clock: Clock,
) {

    fun assembleSystemPrompt(userId: UUID, capacityHint: String): String {
        val settings = userSettingsService.getOrCreate(userId)
        val displayName = settings.displayName?.takeIf { it.isNotBlank() } ?: "there"
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))

        val selection = plannerTaskSelector.select(userId)
        val previousSummary = planningSessionService.findPreviousSummarizableSession(userId)
            ?.summary?.takeIf { it.isNotBlank() }
        val diff = planningSessionService.diffSincePreviousSession(userId)

        val now = clock.instant()
        val weekEnd = now.plus(Duration.ofDays(7))
        val calendar = calendarWindowProvider.describeWindow(userId, now, weekEnd)

        val today = LocalDate.ofInstant(now, zone)

        return templateLoader.load("weekly-planning/system.md").render(mapOf(
            "display_name" to displayName,
            "user_context_block" to (settings.contextBlock?.takeIf { it.isNotBlank() }
                ?: "No personal context shared yet."),
            "previous_session_summary" to (previousSummary ?: "No previous session on record."),
            "task_change_summary" to renderDiff(diff),
            "urgent_tasks" to renderTaskList(selection.urgent),
            "stale_tasks" to renderTaskList(selection.stale),
            "calendar_window" to calendar,
            "capacity_hint" to capacityHint.ifBlank { "Not stated." },
            "today_iso" to today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "user_timezone" to zone.id,
            "preferred_language" to UserSettingsService.SUPPORTED_LANGUAGES.first { settings.preferredLanguage == it.code }.label,
        ))
    }

    fun renderKickoff(capacityHint: String): String =
        templateLoader.load("weekly-planning/kickoff-message.md").render(mapOf(
            "capacity_hint" to capacityHint.ifBlank { "Not stated." },
        ))

    fun renderCapacityQuestion(): String =
        templateLoader.load("weekly-planning/capacity-question.md").render(emptyMap())

    private fun renderTaskList(tasks: List<BacklogTask>): String {
        if (tasks.isEmpty()) return "_(none)_"
        return tasks.joinToString("\n") { task ->
            buildString {
                append("- [").append(task.id).append("] ").append(task.title)
                task.priority?.let { append(" · priority=").append(it.name.lowercase()) }
                task.deadline?.let { append(" · deadline=").append(it) }
                task.estimatedMinutes?.let { append(" · est=").append(it).append("m") }
                if (task.rescheduleCount > 0) append(" · rescheduled=").append(task.rescheduleCount)
                if (task.tags.isNotEmpty()) {
                    append(" · tags=")
                    append(task.tags.joinToString(",") { it.label })
                }
                task.description?.takeIf { it.isNotBlank() }?.let {
                    append("\n    ").append(it.lineSequence().joinToString(" ").trim())
                }
            }
        }
    }

    private fun renderDiff(diff: TaskChangeSummary): String {
        if (diff.totalEvents == 0) return "No backlog changes recorded since the last session."
        val lines = mutableListOf<String>()
        if (diff.completed.isNotEmpty()) {
            lines += "Completed: " + diff.completed.joinToString(", ") { it.title }
        }
        if (diff.createdDuringWindow.isNotEmpty()) {
            lines += "Newly added: " + diff.createdDuringWindow.joinToString(", ") { it.title }
        }
        if (diff.reopened.isNotEmpty()) {
            lines += "Reopened: " + diff.reopened.joinToString(", ") { it.title }
        }
        if (diff.deleted.isNotEmpty()) {
            lines += "Deleted: " + diff.deleted.joinToString(", ") { it.title }
        }
        return lines.joinToString("\n")
    }
}
