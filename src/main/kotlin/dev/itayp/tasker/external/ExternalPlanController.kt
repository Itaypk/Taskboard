package dev.itayp.tasker.external

import dev.itayp.tasker.oneoff.CancelOutcome
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.DirectPlanResult
import dev.itayp.tasker.planning.DirectPlanService
import dev.itayp.tasker.planning.DirectPlanSlot
import dev.itayp.tasker.planning.DirectPlanTask
import dev.itayp.tasker.planning.InviteDeliveryResolver
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Weekly plans for a caller that does its own planning (issue #377): read a week, replace its plan,
 * cancel a one-off event. There is no conversation here, and no draft: the caller submits the
 * finished plan and Backlog provides the display, calendar delivery and board sharing.
 *
 * A thin adapter like the rest of the external API. Writes go through [DirectPlanService], which
 * reaches the same finalization path as the built-in planner; reads compose the existing
 * membership-scoped services. Writing needs a `plan`-scoped token (enforced on the filter chain),
 * because a plan change sends calendar invitations.
 */
@RestController
@RequestMapping("/api/external/v1")
class ExternalPlanController(
    private val directPlanService: DirectPlanService,
    private val planningSessionService: PlanningSessionService,
    private val plannedTaskService: PlannedTaskService,
    private val backlogTaskService: BacklogTaskService,
    private val boardService: BoardService,
    private val oneOffEventService: OneOffEventService,
    private val inviteDeliveryResolver: InviteDeliveryResolver,
) {

    /** A week's plan and events. 200 with `plan: null` for a week that was never planned. */
    @GetMapping("/plans/week/{week}")
    fun getWeek(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable week: String,
        @RequestParam(required = false, defaultValue = "false") includeHidden: Boolean,
    ): ResponseEntity<*> {
        val userId = principal.userId
        val weekStart = resolveWeek(userId, week) ?: return badRequest(weekFormatDetail(week))
        directPlanService.weekStartProblem(userId, weekStart)?.let { return badRequest(it) }
        val session = planningSessionService.findPlanForWeek(userId, weekStart)
        return ResponseEntity.ok(weekResponse(userId, weekStart, session, includeHidden))
    }

    /**
     * Replaces the week's plan with the submitted one. 201 when it is the week's first plan, 200 when
     * it revises an existing one. The body is the week as it now stands, the same shape as the GET.
     */
    @PutMapping("/plans/week/{week}")
    fun submitPlan(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable week: String,
        @RequestParam(required = false, defaultValue = "false") includeHidden: Boolean,
        @RequestBody request: ExternalSubmitPlanRequest,
    ): ResponseEntity<*> {
        val userId = principal.userId
        val weekStart = resolveWeek(userId, week) ?: return badRequest(weekFormatDetail(week))
        val rawTasks = request.tasks
            ?: return badRequest("'tasks' is required: the whole plan, as [{taskId, slots: [{start, end}]}]. An empty list clears the plan.")
        if ((request.note?.length ?: 0) > MAX_NOTE_LENGTH) return badRequest("'note' is longer than $MAX_NOTE_LENGTH characters.")

        val tasks = rawTasks.mapIndexed { index, input ->
            parseTask(index, input).fold(onSuccess = { it }, onFailure = { return badRequest(it.message ?: "Invalid task.") })
        }

        return when (val result = directPlanService.submit(userId, weekStart, tasks, request.note)) {
            is DirectPlanResult.Applied -> {
                logger.debug("External API submitted plan {} (created={})", result.session.id, result.created)
                ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
                    .body(weekResponse(userId, weekStart, result.session, includeHidden))
            }
            is DirectPlanResult.Rejected -> problem(
                when (result.kind) {
                    DirectPlanResult.Kind.INVALID -> HttpStatus.BAD_REQUEST
                    DirectPlanResult.Kind.UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT
                    DirectPlanResult.Kind.CONFLICT -> HttpStatus.CONFLICT
                },
                result.detail,
            )
        }
    }

    /**
     * Cancels a one-off event before it starts, sending a calendar cancellation. Cancelling an
     * already-cancelled event succeeds (204).
     */
    @PostMapping("/events/{eventId}/cancel")
    fun cancelEvent(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable eventId: UUID,
    ): ResponseEntity<*> =
        when (oneOffEventService.cancelEvent(principal.userId, eventId)) {
            is CancelOutcome.Cancelled, CancelOutcome.AlreadyCancelled -> {
                logger.info("External API cancelled event {}", eventId)
                ResponseEntity.noContent().build<Void>()
            }
            CancelOutcome.NotFound -> problem(HttpStatus.NOT_FOUND, "No event $eventId among your events.")
            CancelOutcome.AlreadyStarted -> problem(
                HttpStatus.CONFLICT, "Event $eventId has already started, so it can no longer be cancelled.",
            )
        }

    private fun weekResponse(
        userId: UUID,
        weekStart: LocalDate,
        session: PlanningSession?,
        includeHidden: Boolean,
    ): ExternalWeekPlanResponse {
        val week = directPlanService.week(userId, weekStart)
        return ExternalWeekPlanResponse(
            weekStart = weekStart.toString(),
            weekEnd = weekStart.plusDays(6).toString(),
            timeZone = week.zone.id,
            calendarDelivery = calendarDelivery(userId),
            plan = session?.let { planResponse(userId, it, includeHidden) },
            events = oneOffEventService.listForLocalWeek(userId, weekStart).map { it.toExternalResponse(week.zone) },
        )
    }

    private fun planResponse(userId: UUID, session: PlanningSession, includeHidden: Boolean): ExternalPlanResponse {
        val planned = plannedTaskService.findForSession(userId, session.id)
        // Resolved by id, membership-scoped: a task on a board the user has since left drops out.
        val tasks = backlogTaskService.findTasksAcrossBoards(userId, planned.map { it.taskId }).associateBy { it.id }
        val boardNames = boardService.listBoardsForUser(userId).associate { it.id to it.name }
        val visible = planned.filter { p -> tasks[p.taskId]?.let { includeHidden || !it.hiddenFromAssistant } == true }
        return ExternalPlanResponse(
            id = session.id.toString(),
            finalizedAt = session.endedAt?.toString(),
            weekNote = session.weekNote,
            revisionNotes = session.revisionNotes.map { ExternalRevisionNoteResponse(it.at.toString(), it.text) },
            tasks = visible.map { p ->
                val task = tasks.getValue(p.taskId)
                ExternalPlanTaskResponse(
                    task = task.toExternalResponse(boardNames[task.boardId] ?: ""),
                    slots = p.slots.map { ExternalSlotResponse(it.startIso, it.endIso, it.label) },
                    notes = p.notes,
                )
            },
            hiddenTasksOmitted = if (includeHidden) 0 else planned.count { p -> tasks[p.taskId]?.hiddenFromAssistant == true },
        )
    }

    private fun calendarDelivery(userId: UUID): String = when {
        inviteDeliveryResolver.resolveEmailContext(userId) != null -> "email"
        inviteDeliveryResolver.deliversToFeed(userId) -> "feed"
        else -> "none"
    }

    private fun resolveWeek(userId: UUID, raw: String): LocalDate? = when (raw.lowercase()) {
        "current" -> planningSessionService.currentWeekStart(userId)
        "next" -> planningSessionService.currentWeekStart(userId).plusDays(7)
        else -> runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private fun weekFormatDetail(raw: String) =
        "'$raw' is not a week. Use 'current', 'next', or the week's first day as YYYY-MM-DD."

    private fun parseTask(index: Int, input: ExternalPlanTaskInput): Result<DirectPlanTask> = runCatching {
        val where = "tasks[$index]"
        val taskId = input.taskId?.toUuidOrNull() ?: fail("$where.taskId is missing or not a valid task id.")
        if ((input.notes?.length ?: 0) > MAX_TASK_NOTES_LENGTH) fail("$where.notes is longer than $MAX_TASK_NOTES_LENGTH characters.")
        val slots = input.slots.orEmpty().mapIndexed { slotIndex, slot ->
            val at = "$where.slots[$slotIndex]"
            if ((slot.label?.length ?: 0) > MAX_LABEL_LENGTH) fail("$at.label is longer than $MAX_LABEL_LENGTH characters.")
            DirectPlanSlot(
                start = parseTime(slot.start) ?: fail("$at.start $TIME_FORMAT"),
                end = parseTime(slot.end) ?: fail("$at.end $TIME_FORMAT"),
                label = slot.label,
            )
        }
        DirectPlanTask(taskId = taskId, slots = slots, notes = input.notes)
    }

    private fun parseTime(raw: String?): OffsetDateTime? =
        raw?.let { try { OffsetDateTime.parse(it) } catch (_: DateTimeParseException) { null } }

    private fun fail(detail: String): Nothing = throw IllegalArgumentException(detail)

    private fun OneOffEvent.toExternalResponse(zone: ZoneId) = ExternalEventResponse(
        id = id.toString(),
        title = title,
        start = startsAt.atZone(zone).toOffsetDateTime().toString(),
        end = endsAt.atZone(zone).toOffsetDateTime().toString(),
        location = location,
        notes = notes,
    )

    private fun badRequest(detail: String) = problem(HttpStatus.BAD_REQUEST, detail)

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalPlanController::class.java)
        private const val MAX_NOTE_LENGTH = 2000
        private const val MAX_TASK_NOTES_LENGTH = 1000
        private const val MAX_LABEL_LENGTH = 200
        private const val TIME_FORMAT =
            "is missing or not an ISO-8601 date-time with an offset, e.g. 2026-11-17T09:00:00+02:00."
    }
}
