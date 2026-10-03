package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyDigestComposerTest {

    private val planningSessionService: PlanningSessionService = mock()
    private val plannedTaskService: PlannedTaskService = mock()
    private val backlogTaskService: BacklogTaskService = mock()
    private val muteService: DeadlineReminderMuteService = mock()
    private val composer = DailyDigestComposer(planningSessionService, plannedTaskService, backlogTaskService, muteService)

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val today = LocalDate.parse("2026-10-05")

    private fun withPlan(vararg tasks: AgreedPlanTask) {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(
            PlanningSession(
                id = sessionId, userId = userId, conversationId = null,
                status = PlanningSessionStatus.COMPLETED, startedAt = Instant.parse("2026-10-04T08:00:00Z"),
                weekStart = LocalDate.parse("2026-10-04"), endedAt = null, summary = null,
            ),
        )
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(tasks.toList())
    }

    private fun planned(taskId: UUID, vararg startIsos: String) = AgreedPlanTask(
        taskId = taskId,
        title = "snapshot title",
        slots = startIsos.map { AgreedTimeSlot(startIso = it, endIso = it) },
    )

    private fun withMutes(vararg mutes: DeadlineReminderMuteEntity) {
        whenever(muteService.findMutes(userId)).thenReturn(mutes.associateBy { it.backlogTaskId!! })
    }

    @Test
    fun `today lists only open tasks with a block starting today, by local start time, with live titles`() {
        val gym = digestTask("Gym")
        val report = digestTask("Write report")
        val done = digestTask("Already done", status = TaskStatus.DONE)
        val lateNight = digestTask("Late night")
        val tomorrow = digestTask("Tomorrow's thing")
        withPlan(
            planned(gym.id, "2026-10-05T14:00:00Z"), // 17:00 local
            planned(report.id, "2026-10-05T06:30:00Z"), // 09:30 local
            planned(done.id, "2026-10-05T07:00:00Z"),
            // Still the 4th in UTC, but already the 5th (01:30) locally: the user's zone decides "today".
            planned(lateNight.id, "2026-10-04T22:30:00Z"),
            planned(tomorrow.id, "2026-10-06T07:00:00Z"),
        )
        whenever(backlogTaskService.findTasksAcrossBoards(any(), any())).thenReturn(listOf(gym, report, done, lateNight))

        val content = composer.compose(userId, today, zone, includeDue = false)

        assertEquals(
            listOf(
                DailyDigestContent.PlannedBlock("Late night", LocalTime.of(1, 30)),
                DailyDigestContent.PlannedBlock("Write report", LocalTime.of(9, 30)),
                DailyDigestContent.PlannedBlock("Gym", LocalTime.of(17, 0)),
            ),
            content.today,
        )
        verify(backlogTaskService, never()).findDueTasksAcrossBoards(any(), any())
    }

    @Test
    fun `due excludes planned, tutorial, others' and muted tasks, and orders by deadline then priority`() {
        val inPlan = digestTask("In plan", deadline = today)
        withPlan(planned(inPlan.id, "2026-10-08T07:00:00Z"))
        val overdueLow = digestTask("Overdue low", deadline = today.minusDays(3), priority = TaskPriority.LOW)
        val overdueHigh = digestTask("Overdue high", deadline = today.minusDays(3), priority = TaskPriority.HIGH)
        val dueToday = digestTask("Due today", deadline = today)
        val mine = digestTask("Assigned to me", deadline = today, assigneeUserId = userId)
        val tutorial = digestTask("Tutorial", deadline = today, tutorial = true)
        val theirs = digestTask("Assigned to someone else", deadline = today, assigneeUserId = UUID.randomUUID())
        val muted = digestTask("Muted", deadline = today.minusDays(1))
        val mutedOldDeadline = digestTask("Muted at an older deadline", deadline = today)
        whenever(backlogTaskService.findDueTasksAcrossBoards(userId, today)).thenReturn(
            listOf(inPlan, dueToday, overdueLow, mine, tutorial, theirs, overdueHigh, muted, mutedOldDeadline),
        )
        withMutes(
            mute(muted.id, deadline = today.minusDays(1), until = today.plusDays(2)),
            // Deadline moved since the mute: reminders come back.
            mute(mutedOldDeadline.id, deadline = today.minusDays(7), until = today.plusYears(1)),
        )

        val content = composer.compose(userId, today, zone, includeDue = true)

        assertEquals(
            listOf("Overdue high", "Overdue low", "Due today", "Assigned to me", "Muted at an older deadline"),
            content.due.map { it.title },
        )
        assertEquals(0, content.dueOverflow)
    }

    @Test
    fun `an expired mute no longer silences the task`() {
        withPlan()
        val task = digestTask("Back again", deadline = today.minusDays(10))
        whenever(backlogTaskService.findDueTasksAcrossBoards(userId, today)).thenReturn(listOf(task))
        withMutes(mute(task.id, deadline = today.minusDays(10), until = today))

        assertEquals(listOf("Back again"), composer.compose(userId, today, zone, includeDue = true).due.map { it.title })
    }

    @Test
    fun `due is capped with an overflow count`() {
        withPlan()
        val tasks = (1..13).map { digestTask("Task $it", deadline = today) }
        whenever(backlogTaskService.findDueTasksAcrossBoards(userId, today)).thenReturn(tasks)
        withMutes()

        val content = composer.compose(userId, today, zone, includeDue = true)

        assertEquals(DailyDigestComposer.MAX_DUE_TASKS, content.due.size)
        assertEquals(3, content.dueOverflow)
    }

    @Test
    fun `no plan and nothing due makes an empty digest`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        whenever(backlogTaskService.findDueTasksAcrossBoards(userId, today)).thenReturn(emptyList())
        withMutes()

        assertTrue(composer.compose(userId, today, zone, includeDue = true).isEmpty)
    }

    private fun mute(taskId: UUID, deadline: LocalDate, until: LocalDate) = DeadlineReminderMuteEntity().apply {
        this.userId = this@DailyDigestComposerTest.userId
        backlogTaskId = taskId
        this.deadline = deadline
        mutedUntil = until
    }
}
