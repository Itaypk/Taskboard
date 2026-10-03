package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.service.BacklogTaskService
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** What a daily digest says, before rendering. Titles are decrypted and must never be logged. */
data class DailyDigestContent(
    /** Today's planned blocks, by start time. */
    val today: List<PlannedBlock>,
    /** Due tasks outside the plan, already capped; these are the ones the mute buttons act on. */
    val due: List<DueTask>,
    /** How many more due tasks there were beyond [due]. */
    val dueOverflow: Int,
) {
    val isEmpty: Boolean get() = today.isEmpty() && due.isEmpty()

    data class PlannedBlock(val title: String, val start: LocalTime)

    data class DueTask(val taskId: UUID, val title: String, val deadline: LocalDate)
}

/**
 * Computes the daily digest's two sections for a user (see docs/DAILY-DIGEST.md, "What's in the
 * digest, precisely"):
 *  - **today**: blocks in the current week's finalized plan that start today, for tasks still open;
 *  - **due**: open tasks outside that plan whose deadline is today or earlier, minus active mutes.
 */
@Component
class DailyDigestComposer(
    private val planningSessionService: PlanningSessionService,
    private val plannedTaskService: PlannedTaskService,
    private val backlogTaskService: BacklogTaskService,
    private val muteService: DeadlineReminderMuteService,
) {

    fun compose(userId: UUID, today: LocalDate, zone: ZoneId, includeDue: Boolean): DailyDigestContent {
        val planned = planningSessionService.findCurrentPlan(userId)
            ?.let { plannedTaskService.findForSession(userId, it.id) }
            .orEmpty()

        val due = if (includeDue) dueTasks(userId, planned, today) else emptyList()
        return DailyDigestContent(
            today = todayBlocks(userId, planned, today, zone),
            due = due.take(MAX_DUE_TASKS),
            dueOverflow = (due.size - MAX_DUE_TASKS).coerceAtLeast(0),
        )
    }

    private fun todayBlocks(
        userId: UUID,
        planned: List<AgreedPlanTask>,
        today: LocalDate,
        zone: ZoneId,
    ): List<DailyDigestContent.PlannedBlock> {
        val startsToday = planned.flatMap { task ->
            task.slots.mapNotNull { slot ->
                val start = runCatching { OffsetDateTime.parse(slot.startIso).atZoneSameInstant(zone) }.getOrNull()
                if (start?.toLocalDate() == today) task.taskId to start else null
            }
        }
        if (startsToday.isEmpty()) return emptyList()

        // Live backlog state, not the planning snapshot: drop what's since been done, archived or
        // deleted, and pick up renames.
        val openTasks = backlogTaskService
            .findTasksAcrossBoards(userId, startsToday.map { it.first }.toSet())
            .filter { it.status == TaskStatus.TODO }
            .associateBy { it.id }
        return startsToday
            .mapNotNull { (taskId, start) -> openTasks[taskId]?.let { start to it.title } }
            .sortedBy { (start, _) -> start }
            .map { (start, title) -> DailyDigestContent.PlannedBlock(title, start.toLocalTime()) }
    }

    private fun dueTasks(userId: UUID, planned: List<AgreedPlanTask>, today: LocalDate): List<DailyDigestContent.DueTask> {
        val plannedIds = planned.map { it.taskId }.toSet()
        val mutes = muteService.findMutes(userId)
        return backlogTaskService.findDueTasksAcrossBoards(userId, today)
            .filter { task ->
                val deadline = task.deadline ?: return@filter false
                task.id !in plannedIds &&
                    !task.tutorial &&
                    // A task assigned to another member is theirs to worry about.
                    (task.assigneeUserId == null || task.assigneeUserId == userId) &&
                    mutes[task.id]?.silences(deadline, today) != true
            }
            .sortedWith(DUE_ORDER)
            .map { DailyDigestContent.DueTask(it.id, it.title, it.deadline!!) }
    }

    companion object {
        const val MAX_DUE_TASKS = 10

        /** Oldest deadline first, then highest priority (unset priority last). */
        private val DUE_ORDER: Comparator<BacklogTask> =
            compareBy<BacklogTask> { it.deadline }.thenByDescending { it.priority?.ordinal ?: -1 }
    }
}
