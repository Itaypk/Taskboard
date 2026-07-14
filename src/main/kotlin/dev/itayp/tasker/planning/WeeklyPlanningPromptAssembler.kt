package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.BoardService
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
    private val categoryService: BacklogTaskCategoryService,
    private val tagService: BacklogTaskTagService,
    private val boardService: BoardService,
    private val aiAccessService: AiAccessService,
    private val inviteDeliveryResolver: InviteDeliveryResolver,
    private val clock: Clock,
) {

    fun assembleSystemPrompt(
        userId: UUID,
        capacityHint: String,
        weekStart: LocalDate,
        formatter: MessageFormatter,
        carriedOver: List<UnfinishedPlannedTask> = emptyList(),
    ): String {
        val settings = userSettingsService.getOrCreate(userId)
        val displayName = settings.displayName?.takeIf { it.isNotBlank() } ?: "there"
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))

        val today = LocalDate.ofInstant(clock.instant(), zone)
        val selection = plannerTaskSelector.select(userId, today, weekStart, zone)
        // Drop boards a co-member has opted out of: the LLM mustn't see their categories/tags or
        // be allowed to target them via create_task. PlannerTaskSelector applies the same filter.
        val allowedBoardIds = aiAccessService.aiAllowedBoardIds(userId)
        val boards = boardService.listBoardsForUser(userId).filter { it.id in allowedBoardIds }
        val previousSummary = planningSessionService.findPreviousSummarizableSession(userId, weekStart)
            ?.summary?.takeIf { it.isNotBlank() }
        val diff = planningSessionService.diffSincePreviousSession(userId, weekStart)

        val weekEnd = weekStart.plusDays(7)
        val calendarFrom = weekStart.atStartOfDay(zone).toInstant()
        val calendarTo = weekEnd.atStartOfDay(zone).toInstant()
        val calendar = calendarWindowProvider.describeWindow(userId, calendarFrom, calendarTo)

        return templateLoader.load("weekly-planning/system.md").render(mapOf(
            "display_name" to displayName,
            "staying_on_task" to renderStayingOnTask(),
            "user_context_block" to (settings.contextBlock?.takeIf { it.isNotBlank() }
                ?: "No personal context shared yet."),
            "previous_session_summary" to (previousSummary ?: "No previous session on record."),
            "task_change_summary" to renderDiff(diff),
            "carried_over_tasks" to renderCarriedOver(carriedOver),
            "urgent_tasks" to renderTaskList(selection.urgent, selection.alreadyPlanned, selection.alreadyScheduled, selection.boardNames),
            "stale_tasks" to renderTaskList(selection.stale, selection.alreadyPlanned, selection.alreadyScheduled, selection.boardNames),
            "categories" to renderCategoriesForBoards(userId, boards),
            "tags" to renderTagsForBoards(userId, boards),
            "calendar_window" to calendar,
            "delivery_methods" to inviteDeliveryResolver.describeDeliveryMethods(userId),
            "capacity_hint" to capacityHint.ifBlank { "Not stated." },
            "today_iso" to today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "week_start_iso" to weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "week_end_iso" to weekStart.plusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE),
            "user_timezone" to zone.id,
            "preferred_language" to (UserSettingsService.SUPPORTED_LANGUAGES.firstOrNull { settings.preferredLanguage == it.code }?.label ?: settings.preferredLanguage),
            "user_gender" to genderInstruction(settings.gender),
            "assistant_name" to ASSISTANT_NAME,
            "assistant_gender" to ASSISTANT_GENDER,
            "formatting_guidance" to formatter.promptGuidance(),
        ))
    }

    fun renderKickoff(capacityHint: String, carriedOver: List<UnfinishedPlannedTask> = emptyList()): String =
        templateLoader.load("weekly-planning/kickoff-message.md").render(mapOf(
            "capacity_hint" to capacityHint.ifBlank { "Not stated." },
            "carried_over_note" to renderCarriedOverNote(carriedOver),
        ))

    /**
     * Builds the system prompt for a revise session. Unlike [assembleSystemPrompt], this drops
     * the capacity hint and the urgent/stale candidate lists — the conversation is about
     * editing an existing plan, not proposing one from scratch.
     */
    fun assembleRevisionSystemPrompt(
        userId: UUID,
        session: PlanningSession,
        currentPlanTasks: List<AgreedPlanTask>,
        formatter: MessageFormatter,
    ): String {
        val settings = userSettingsService.getOrCreate(userId)
        val displayName = settings.displayName?.takeIf { it.isNotBlank() } ?: "there"
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))

        val today = LocalDate.ofInstant(clock.instant(), zone)
        val weekStart = session.weekStart
        val previousSummary = session.summary?.takeIf { it.isNotBlank() }
        val diff = planningSessionService.diffSincePreviousSession(userId, weekStart)

        val weekEnd = weekStart.plusDays(7)
        val calendarFrom = weekStart.atStartOfDay(zone).toInstant()
        val calendarTo = weekEnd.atStartOfDay(zone).toInstant()
        val calendar = calendarWindowProvider.describeWindow(userId, calendarFrom, calendarTo)

        val plannedAt = session.endedAt ?: session.startedAt
        val daysSinceCompleted = Duration.between(plannedAt, clock.instant()).toDays()
        // Drop boards a co-member has opted out of: the LLM mustn't see their categories/tags or
        // be allowed to target them via create_task. PlannerTaskSelector applies the same filter.
        val allowedBoardIds = aiAccessService.aiAllowedBoardIds(userId)
        val boards = boardService.listBoardsForUser(userId).filter { it.id in allowedBoardIds }

        return templateLoader.load("weekly-planning/revise-system.md").render(mapOf(
            "display_name" to displayName,
            "staying_on_task" to renderStayingOnTask(),
            "user_context_block" to (settings.contextBlock?.takeIf { it.isNotBlank() }
                ?: "No personal context shared yet."),
            "previous_plan_summary" to (previousSummary ?: "_(no summary recorded)_"),
            "current_plan" to renderCurrentPlan(currentPlanTasks),
            "categories" to renderCategoriesForBoards(userId, boards),
            "tags" to renderTagsForBoards(userId, boards),
            "plan_finalized_at" to plannedAt.toString(),
            "days_since_finalized" to daysSinceCompleted.toString(),
            "task_change_summary" to renderDiff(diff),
            "calendar_window" to calendar,
            "delivery_methods" to inviteDeliveryResolver.describeDeliveryMethods(userId),
            "today_iso" to today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "week_start_iso" to weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "week_end_iso" to weekStart.plusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE),
            "user_timezone" to zone.id,
            "preferred_language" to (UserSettingsService.SUPPORTED_LANGUAGES.firstOrNull { settings.preferredLanguage == it.code }?.label ?: settings.preferredLanguage),
            "user_gender" to genderInstruction(settings.gender),
            "assistant_name" to ASSISTANT_NAME,
            "assistant_gender" to ASSISTANT_GENDER,
            "formatting_guidance" to formatter.promptGuidance(),
        ))
    }

    fun renderReviseKickoff(): String =
        templateLoader.load("weekly-planning/revise-kickoff-message.md").render(emptyMap())

    private fun renderCurrentPlan(tasks: List<AgreedPlanTask>): String {
        if (tasks.isEmpty()) return "_(the plan has no scheduled tasks)_"
        return tasks.joinToString("\n") { task ->
            buildString {
                append("- [").append(task.taskId).append("] ")
                append(task.title)
                if (task.slots.isNotEmpty()) {
                    append("\n    slots:")
                    task.slots.forEach { slot ->
                        append("\n      • ").append(slot.startIso).append(" → ").append(slot.endIso)
                        slot.label?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
                    }
                }
                task.notes?.takeIf { it.isNotBlank() }?.let {
                    append("\n    notes: ").append(it.lineSequence().joinToString(" ").trim())
                }
            }
        }
    }

    /** Shared scope/off-topic guidance, kept in one file so both prompts stay in sync. */
    private fun renderStayingOnTask(): String =
        templateLoader.load("weekly-planning/staying-on-task.md").render(emptyMap())

    private fun renderCategoryLines(categories: List<BacklogTaskCategory>): String {
        if (categories.isEmpty()) return "_(none)_"
        return categories.joinToString("\n") { "- [${it.id}] ${it.label}" }
    }

    private fun renderTagLines(tags: List<BacklogTaskTag>): String {
        if (tags.isEmpty()) return "_(none yet)_"
        return tags.joinToString("\n") { "- [${it.id}] ${it.label} (${it.colorId.name.lowercase()})" }
    }

    /**
     * Categories for `create_task`. With a single board, a flat list as before. With several, the
     * list is grouped under a board header carrying the `board_id` so the model can target a board
     * via `create_task(board_id=…)`; the first board is the default (used when `board_id` is omitted).
     */
    private fun renderCategoriesForBoards(userId: UUID, boards: List<BoardSummary>): String {
        if (boards.size <= 1) {
            val boardId = boards.firstOrNull()?.id ?: return "_(none)_"
            return renderCategoryLines(categoryService.getCategories(userId, boardId))
        }
        return boards.withIndex().joinToString("\n\n") { (i, board) ->
            val header = "Board \"${board.name}\" (board_id: ${board.id})${if (i == 0) " — default" else ""}"
            "$header\n" + renderCategoryLines(categoryService.getCategories(userId, board.id))
        }
    }

    private fun renderTagsForBoards(userId: UUID, boards: List<BoardSummary>): String {
        if (boards.size <= 1) {
            val boardId = boards.firstOrNull()?.id ?: return "_(none yet)_"
            return renderTagLines(tagService.getTags(userId, boardId))
        }
        return boards.joinToString("\n\n") { board ->
            "Board \"${board.name}\" (board_id: ${board.id})\n" + renderTagLines(tagService.getTags(userId, board.id))
        }
    }

    private fun genderInstruction(gender: String?): String = when (gender) {
        "masculine" -> "masculine grammatical gender (he/him forms)"
        "feminine" -> "feminine grammatical gender (she/her forms)"
        else -> "gender-neutral language (they/them forms or avoid gendering)"
    }

    private fun renderTaskList(
        tasks: List<BacklogTask>,
        alreadyPlanned: Map<UUID, LocalDate>,
        alreadyScheduled: Map<UUID, LocalDate>,
        boardNames: Map<UUID, String>,
    ): String {
        if (tasks.isEmpty()) return "_(none)_"
        // Only label boards when the user has more than one — a single-board user sees no change.
        val showBoard = boardNames.size > 1
        return tasks.joinToString("\n") { task ->
            buildString {
                append("- [").append(task.id).append("] ").append(task.title)
                if (showBoard) boardNames[task.boardId]?.let { append(" · board=").append(it) }
                task.priority?.let { append(" · priority=").append(it.name.lowercase()) }
                task.deadline?.let { append(" · deadline=").append(it) }
                task.estimatedMinutes?.let { append(" · est=").append(it).append("m") }
                task.relevantFrom?.let { append(" · relevant_from=").append(it) }
                if (task.rescheduleCount > 0) append(" · rescheduled=").append(task.rescheduleCount)
                alreadyPlanned[task.id]?.let { append(" · already_planned=").append(it) }
                alreadyScheduled[task.id]?.let { append(" · already_scheduled=").append(it) }
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

    /**
     * The carry-over section for the system prompt: tasks the user, in the reconciliation sweep,
     * explicitly asked to pull into this week. Rendered like the candidate lists (`[id] title`) so the
     * model has a real `task_id` to schedule and submit.
     */
    private fun renderCarriedOver(tasks: List<UnfinishedPlannedTask>): String {
        if (tasks.isEmpty()) return "_(none)_"
        return tasks.joinToString("\n") { "- [${it.taskId}] ${it.title}" }
    }

    /** A short kickoff note naming the carried-over tasks by title (ids stay out of the user-facing turn). */
    private fun renderCarriedOverNote(tasks: List<UnfinishedPlannedTask>): String {
        if (tasks.isEmpty()) return ""
        val titles = tasks.joinToString(", ") { it.title }
        return " The user chose to carry these unfinished tasks into this week: $titles."
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

    companion object {
        const val ASSISTANT_NAME = "Backlog"
        const val ASSISTANT_GENDER = "neutral"
    }
}
