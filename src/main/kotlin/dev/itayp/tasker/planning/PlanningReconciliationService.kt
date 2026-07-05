package dev.itayp.tasker.planning

import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.service.BacklogTaskService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Finds the tasks that need reconciling at the *start* of a planning session — the "did you
 * actually do it?" sweep that runs after the capacity question and before the AI assistant kicks
 * in (see `docs/PLANNING-FLOW.md`, "pre-session reconciliation").
 *
 * A task qualifies when all three hold:
 *  - it belongs to the plan immediately preceding the week being planned (the user's *current*
 *    plan — the most recent COMPLETED session for an earlier week);
 *  - its latest scheduled slot has already ended (so the window to do it has passed); and
 *  - the live backlog task is still `TODO` (not marked done, not archived).
 *
 * Slot end times are absolute instants (ISO offset date-times), so the "already passed" check is
 * timezone-independent — no user zone needed here.
 */
@Service
class PlanningReconciliationService(
    private val planningSessionService: PlanningSessionService,
    private val plannedTaskService: PlannedTaskService,
    private val backlogTaskService: BacklogTaskService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PlanningReconciliationService::class.java)

    fun findUnfinishedTasks(userId: UUID, weekStart: LocalDate): List<UnfinishedPlannedTask> {
        val previous = planningSessionService.findPreviousSummarizableSession(userId, weekStart)
            ?: return emptyList()
        val planned = plannedTaskService.findForSession(userId, previous.id)
        if (planned.isEmpty()) return emptyList()

        val now = clock.instant()
        val unfinished = planned.mapNotNull { task ->
            // No slot, or none we can parse → we can't say its time has "passed"; skip it.
            val latestEnd = task.slots.mapNotNull { parseInstant(it.endIso) }.maxOrNull()
                ?: return@mapNotNull null
            if (!latestEnd.isBefore(now)) return@mapNotNull null // still has a current/future slot
            // Resolve the live task so a rename/done/archive since the plan was written is reflected.
            val live = backlogTaskService.findTask(userId, task.taskId) ?: return@mapNotNull null
            if (live.status != TaskStatus.TODO) return@mapNotNull null
            UnfinishedPlannedTask(taskId = task.taskId, title = live.title)
        }
        log.debug(
            "Reconciliation for user {} week {}: {} unfinished task(s) from session {}",
            userId, weekStart, unfinished.size, previous.id,
        )
        return unfinished
    }

    private fun parseInstant(iso: String): Instant? =
        runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
}

/** A previously-planned task, still open and past its slot, that the user is asked to reconcile. */
data class UnfinishedPlannedTask(
    val taskId: UUID,
    val title: String,
)
