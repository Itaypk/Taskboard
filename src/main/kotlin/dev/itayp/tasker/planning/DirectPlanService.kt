package dev.itayp.tasker.planning

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** One time block in a plan submitted whole by an outside caller. */
data class DirectPlanSlot(val start: OffsetDateTime, val end: OffsetDateTime, val label: String? = null)

/** One task of a plan submitted whole by an outside caller. */
data class DirectPlanTask(val taskId: UUID, val slots: List<DirectPlanSlot>, val notes: String? = null)

sealed interface DirectPlanResult {
    /** The plan was written. [keptHiddenTasks] counts assistant-hidden tasks kept because the caller didn't list them. */
    data class Applied(val session: PlanningSession, val created: Boolean, val keptHiddenTasks: Int) : DirectPlanResult

    /** Nothing was written. [detail] is addressed to the caller (often a model) and says how to fix the request. */
    data class Rejected(val kind: Kind, val detail: String) : DirectPlanResult

    enum class Kind {
        /** The request itself is malformed (shape, ranges). */
        INVALID,

        /** Well-formed, but conflicts with the data (unknown task, done task, past week). */
        UNPROCESSABLE,

        /** An in-app planning conversation for the same week could still finalize over it. */
        CONFLICT,
    }
}

/** A planning week in the user's zone: `[start, end)`. */
data class PlanningWeek(val weekStart: LocalDate, val zone: ZoneId) {
    val start: ZonedDateTime get() = weekStart.atStartOfDay(zone)
    val end: ZonedDateTime get() = weekStart.plusDays(7).atStartOfDay(zone)
}

/**
 * Writes a weekly plan that an outside caller (the external API, issue #377) worked out by itself:
 * no conversation, no draft, just the finished plan. Every write goes through
 * [PlanFinalizationService] — `complete` for a week's first plan, `revisePlan` after — so the
 * slot diff, calendar invitations (or the feed), slot reminders and reschedule counts behave
 * exactly as they do for the built-in planner.
 *
 * What it adds on top is the checking a model-written plan from outside needs: every task must
 * be one the user can reach, the title always comes from the task (the invitations print it), and
 * the slots must fall in the week.
 *
 * A submit replaces the whole plan, with one exception: assistant-hidden tasks already in the plan
 * that the caller didn't list are kept as they are. A caller that respects `hiddenFromAssistant`
 * never sees them, so it can't be expected to send them back, and dropping them would silently
 * cancel their invitations.
 */
@Service
class DirectPlanService(
    private val planningSessionService: PlanningSessionService,
    private val plannedTaskService: PlannedTaskService,
    private val planFinalizationService: PlanFinalizationService,
    private val backlogTaskService: BacklogTaskService,
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val userSettingsService: UserSettingsService,
) {
    private val log = LoggerFactory.getLogger(DirectPlanService::class.java)

    fun week(userId: UUID, weekStart: LocalDate): PlanningWeek {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        return PlanningWeek(weekStart, zone)
    }

    /** Null when [weekStart] is a valid start of a planning week for this user; otherwise why not. */
    fun weekStartProblem(userId: UUID, weekStart: LocalDate): String? {
        val startDay = WeekResolver.parseWeekStartDay(userSettingsService.getOrCreate(userId).weekStartDay)
            ?: DayOfWeek.MONDAY
        if (weekStart.dayOfWeek == startDay) return null
        return "Weeks start on ${startDay.name.lowercase()} for this user, and $weekStart is a " +
            "${weekStart.dayOfWeek.name.lowercase()}. The current week starts on " +
            "${planningSessionService.currentWeekStart(userId)}; 'current' and 'next' are also accepted."
    }

    fun submit(userId: UUID, weekStart: LocalDate, tasks: List<DirectPlanTask>, note: String?): DirectPlanResult {
        weekStartProblem(userId, weekStart)?.let { return invalid(it) }
        val currentWeek = planningSessionService.currentWeekStart(userId)
        if (weekStart < currentWeek) {
            return unprocessable(
                "The week of $weekStart is over; only the current week ($currentWeek) or a later one can be planned.",
            )
        }
        if (tasks.size > MAX_TASKS) return invalid("A plan holds at most $MAX_TASKS tasks; this one has ${tasks.size}.")
        tasks.groupingBy { it.taskId }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
            return invalid("Task $it is listed more than once. List each task once, with all of its slots.")
        }

        val existing = planningSessionService.findPlanForWeek(userId, weekStart)
        val latest = planningSessionService.findSessionForWeek(userId, weekStart)
        if (listOfNotNull(existing, latest).any { orchestrator.hasLiveConversation(it.id) }) {
            return DirectPlanResult.Rejected(
                DirectPlanResult.Kind.CONFLICT,
                "A planning conversation for the week of $weekStart is in progress in the app. " +
                    "Ask the user to finish or abandon it there first, then submit again.",
            )
        }

        val previous = existing?.let { plannedTaskService.findForSession(userId, it.id) }.orEmpty()
        val previousById = previous.associateBy { it.taskId }
        val backlog = backlogTaskService
            .findTasksAcrossBoards(userId, tasks.map { it.taskId } + previous.map { it.taskId })
            .associateBy { it.id }
        val week = week(userId, weekStart)

        val planned = tasks.map { task ->
            val backlogTask = backlog[task.taskId]
                ?: return unprocessable("No task ${task.taskId} on any board you are a member of.")
            checkSlots(task, week)?.let { return invalid(it) }
            checkStatus(backlogTask, task, previousById[task.taskId])?.let { return unprocessable(it) }
            toAgreedTask(task, backlogTask, previousById[task.taskId], week.zone)
        }

        val listed = tasks.map { it.taskId }.toSet()
        val keptHidden = previous.filter { it.taskId !in listed && backlog[it.taskId]?.hiddenFromAssistant == true }
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }

        val sessionId: UUID
        if (existing == null) {
            val session = planningSessionService.openSessionForDirectPlan(userId, weekStart)
            try {
                planFinalizationService.complete(userId, session.id, AgreedPlan(tasks = planned, weekNote = trimmedNote))
            } catch (e: RuntimeException) {
                // Don't leave an ACTIVE row behind: the scheduled planner skips a week that has one.
                runCatching { planningSessionService.abandonSession(userId, session.id) }
                throw e
            }
            sessionId = session.id
        } else {
            planFinalizationService.revisePlan(
                userId, existing.id, AgreedPlan(tasks = planned + keptHidden, revisionNote = trimmedNote),
            )
            sessionId = existing.id
        }
        log.info(
            "Direct plan {} for week {} (session {}, tasks={}, keptHidden={})",
            if (existing == null) "created" else "revised", weekStart, sessionId, planned.size, keptHidden.size,
        )
        val session = planningSessionService.findById(userId, sessionId)
            ?: throw IllegalStateException("Planning session $sessionId vanished after finalizing")
        return DirectPlanResult.Applied(session, created = existing == null, keptHiddenTasks = keptHidden.size)
    }

    private fun checkSlots(task: DirectPlanTask, week: PlanningWeek): String? {
        if (task.slots.isEmpty()) {
            return "Task ${task.taskId} has no slots. Give it at least one, or leave it out to take it off the plan."
        }
        if (task.slots.size > MAX_SLOTS_PER_TASK) {
            return "Task ${task.taskId} has ${task.slots.size} slots; the limit is $MAX_SLOTS_PER_TASK."
        }
        val weekStart = week.start.toInstant()
        val weekEnd = week.end.toInstant()
        task.slots.forEach { slot ->
            val start = slot.start.toInstant()
            val end = slot.end.toInstant()
            if (!end.isAfter(start)) return "A slot of task ${task.taskId} ends (${slot.end}) before it starts (${slot.start})."
            if (Duration.between(start, end) > MAX_SLOT_LENGTH) {
                return "A slot of task ${task.taskId} is longer than ${MAX_SLOT_LENGTH.toHours()} hours. Split it into several slots."
            }
            if (start.isBefore(weekStart) || !start.isBefore(weekEnd)) {
                return "A slot of task ${task.taskId} starts at ${slot.start}, outside the week. Slots must start " +
                    "between ${week.start.toOffsetDateTime()} and ${week.end.toOffsetDateTime()} (the user's time zone is ${week.zone})."
            }
        }
        if (task.slots.map { it.start.toInstant() }.toSet().size != task.slots.size) {
            return "Task ${task.taskId} has two slots starting at the same time."
        }
        return null
    }

    /**
     * A done or archived task may stay in the plan as it was (the plan is a record of the week), but
     * may not be added or moved: that would put a finished task back on the calendar.
     */
    private fun checkStatus(backlogTask: BacklogTask, task: DirectPlanTask, previous: AgreedPlanTask?): String? {
        if (backlogTask.status == TaskStatus.TODO) return null
        val state = backlogTask.status.name.lowercase()
        if (previous == null) return "Task ${task.taskId} is $state, so it can't be added to a plan."
        val before = previous.slots.mapNotNull { parseInstant(it.startIso) }.toSet()
        val after = task.slots.map { it.start.toInstant() }.toSet()
        if (before != after) {
            return "Task ${task.taskId} is $state. It can stay in the plan with its slots unchanged, or be left out, " +
                "but it can't be rescheduled."
        }
        return null
    }

    /**
     * The title comes from the stored plan when the task was already in it (so an unchanged slot
     * doesn't read as "changed" and re-send its invitation over a title wording difference), and
     * from the task otherwise. Never from the caller. Slot timestamps likewise reuse the stored
     * strings when they name the same instant, because the slot diff compares them as text.
     */
    private fun toAgreedTask(task: DirectPlanTask, backlogTask: BacklogTask, previous: AgreedPlanTask?, zone: ZoneId): AgreedPlanTask {
        val previousSlots = previous?.slots.orEmpty()
        val slots = task.slots.map { slot ->
            val start = slot.start.toInstant()
            val end = slot.end.toInstant()
            val same = previousSlots.firstOrNull { parseInstant(it.startIso) == start }
            AgreedTimeSlot(
                startIso = same?.startIso ?: normalize(slot.start, zone),
                endIso = same?.endIso?.takeIf { parseInstant(it) == end } ?: normalize(slot.end, zone),
                label = slot.label?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return AgreedPlanTask(
            taskId = task.taskId,
            title = previous?.title?.takeIf { it.isNotBlank() } ?: backlogTask.title,
            slots = slots,
            notes = task.notes?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun normalize(time: OffsetDateTime, zone: ZoneId): String =
        time.atZoneSameInstant(zone).toOffsetDateTime().toString()

    private fun parseInstant(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

    private fun invalid(detail: String) = DirectPlanResult.Rejected(DirectPlanResult.Kind.INVALID, detail)

    private fun unprocessable(detail: String) = DirectPlanResult.Rejected(DirectPlanResult.Kind.UNPROCESSABLE, detail)

    companion object {
        const val MAX_TASKS = 100
        const val MAX_SLOTS_PER_TASK = 21
        val MAX_SLOT_LENGTH: Duration = Duration.ofHours(24)
    }
}
