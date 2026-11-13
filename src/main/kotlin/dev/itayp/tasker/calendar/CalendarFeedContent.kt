package dev.itayp.tasker.calendar

import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** What reaches a user's calendar from [CalendarFeedContent.collect]. */
data class CalendarContent(
    /** Planned tasks of finalized plans, each holding only its slots that fall in the window. */
    val planTasks: List<AgreedPlanTask>,
    val oneOffEvents: List<OneOffEvent>,
) {
    val isEmpty: Boolean get() = planTasks.isEmpty() && oneOffEvents.isEmpty()
}

/**
 * Gathers the time blocks that belong on a user's calendar: the slots of their finalized weekly
 * plans and their live one-off events. The subscription feed serves this from a little in the past
 * onward; switching between invitations and the feed uses the upcoming part of it.
 *
 * Mirrors what invitations leave on the calendar: a task completed, archived or deleted before its
 * block has had its upcoming invitations cancelled (`TaskCompletionCancellationService`), but its
 * slot rows stay in the plan, so its future slots are dropped here.
 */
@Service
class CalendarFeedContent(
    private val planningSessionRepository: PlanningSessionRepository,
    private val plannedTaskService: PlannedTaskService,
    private val backlogTaskRepository: BacklogTaskRepository,
    private val oneOffEventService: OneOffEventService,
    private val userSettingsService: UserSettingsService,
) {

    /** Blocks ending after [from] (plan slots) or starting after it (one-off events), up to [HORIZON] ahead. */
    @Transactional(readOnly = true)
    fun collect(userId: UUID, from: Instant, now: Instant): CalendarContent {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        // A week can start up to 6 days before its first slot that still ends after `from`.
        val firstWeek = from.atZone(zone).toLocalDate().minusDays(7)
        val sessions = planningSessionRepository
            .findAllByUserIdAndStatusAndWeekStartGreaterThanEqualOrderByWeekStartDescStartedAtDesc(
                userId, PlanningSessionStatus.COMPLETED, firstWeek,
            )
            // Ordered weekStart-desc then startedAt-desc: keep the latest plan per week.
            .distinctBy { it.weekStart }

        val planTasks = sessions.flatMap { plannedTaskService.findForSession(userId, it.id!!) }
        val liveTaskIds = liveTaskIds(planTasks.map { it.taskId }.toSet())
        val windowed = planTasks.mapNotNull { task ->
            val slots = task.slots.filter { slot ->
                val start = parse(slot.startIso) ?: return@filter false
                val end = parse(slot.endIso) ?: return@filter false
                end.isAfter(from) && (task.taskId in liveTaskIds || !start.isAfter(now))
            }
            if (slots.isEmpty()) null else task.copy(slots = slots)
        }

        val oneOffs = oneOffEventService.listForWeek(userId, from, now.plus(HORIZON))
        return CalendarContent(windowed, oneOffs)
    }

    /** Of [taskIds], those that still exist and aren't done or archived. */
    private fun liveTaskIds(taskIds: Set<UUID>): Set<UUID> {
        if (taskIds.isEmpty()) return emptySet()
        return backlogTaskRepository.findAllById(taskIds)
            .filter { it.status == TaskStatus.TODO }
            .mapNotNull { it.id }
            .toSet()
    }

    private fun parse(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

    companion object {
        /** How far ahead one-off events are served. Plans are served in full whatever their week. */
        val HORIZON: Duration = Duration.ofDays(366)
    }
}
