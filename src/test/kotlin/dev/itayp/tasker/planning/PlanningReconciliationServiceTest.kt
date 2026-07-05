package dev.itayp.tasker.planning

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PlanningReconciliationServiceTest {

    @Mock private lateinit var planningSessionService: PlanningSessionService
    @Mock private lateinit var plannedTaskService: PlannedTaskService
    @Mock private lateinit var backlogTaskService: BacklogTaskService

    // "Now" is Monday 2026-05-04; the week being planned starts here, last week's slots are in the past.
    private val now: Instant = Instant.parse("2026-05-04T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val weekStart: LocalDate = LocalDate.parse("2026-05-04")

    private val service by lazy {
        PlanningReconciliationService(planningSessionService, plannedTaskService, backlogTaskService, clock)
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val prevSessionId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    @Test
    fun `no previous session yields nothing to reconcile`() {
        whenever(planningSessionService.findPreviousSummarizableSession(userId, weekStart)).thenReturn(null)

        assertTrue(service.findUnfinishedTasks(userId, weekStart).isEmpty())
    }

    @Test
    fun `a past-due still-open task is surfaced with its live title`() {
        val taskId = UUID.randomUUID()
        stubPreviousSession()
        whenever(plannedTaskService.findForSession(userId, prevSessionId)).thenReturn(
            listOf(plannedTask(taskId, "Old snapshot title", pastSlot())),
        )
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(task(taskId, "Live title", TaskStatus.TODO))

        val result = service.findUnfinishedTasks(userId, weekStart)

        assertEquals(1, result.size)
        assertEquals(taskId, result[0].taskId)
        assertEquals("Live title", result[0].title) // live task wins over the plan snapshot
    }

    @Test
    fun `a task already marked done is excluded`() {
        val taskId = UUID.randomUUID()
        stubPreviousSession()
        whenever(plannedTaskService.findForSession(userId, prevSessionId)).thenReturn(
            listOf(plannedTask(taskId, "Done task", pastSlot())),
        )
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(task(taskId, "Done task", TaskStatus.DONE))

        assertTrue(service.findUnfinishedTasks(userId, weekStart).isEmpty())
    }

    @Test
    fun `a task whose latest slot is still in the future is excluded`() {
        val taskId = UUID.randomUUID()
        stubPreviousSession()
        // One passed slot AND one upcoming slot: the window hasn't fully passed, so don't nag.
        whenever(plannedTaskService.findForSession(userId, prevSessionId)).thenReturn(
            listOf(plannedTask(taskId, "Ongoing", pastSlot(), futureSlot())),
        )

        assertTrue(service.findUnfinishedTasks(userId, weekStart).isEmpty())
    }

    @Test
    fun `a task with no parseable slot is excluded`() {
        val taskId = UUID.randomUUID()
        stubPreviousSession()
        whenever(plannedTaskService.findForSession(userId, prevSessionId)).thenReturn(
            listOf(AgreedPlanTask(taskId = taskId, title = "No slots", slots = emptyList())),
        )

        assertTrue(service.findUnfinishedTasks(userId, weekStart).isEmpty())
    }

    @Test
    fun `a task that no longer exists in the backlog is excluded`() {
        val taskId = UUID.randomUUID()
        stubPreviousSession()
        whenever(plannedTaskService.findForSession(userId, prevSessionId)).thenReturn(
            listOf(plannedTask(taskId, "Deleted", pastSlot())),
        )
        whenever(backlogTaskService.findTask(userId, taskId)).thenReturn(null)

        assertTrue(service.findUnfinishedTasks(userId, weekStart).isEmpty())
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    private fun stubPreviousSession() {
        whenever(planningSessionService.findPreviousSummarizableSession(userId, weekStart)).thenReturn(
            PlanningSession(
                id = prevSessionId,
                userId = userId,
                conversationId = null,
                status = PlanningSessionStatus.COMPLETED,
                startedAt = now.minusSeconds(7 * 24 * 3600),
                weekStart = weekStart.minusWeeks(1),
                endedAt = now.minusSeconds(7 * 24 * 3600),
                summary = "last week",
            ),
        )
    }

    private fun pastSlot() = AgreedTimeSlot(
        startIso = "2026-04-29T10:00:00Z",
        endIso = "2026-04-29T11:00:00Z", // before `now`
    )

    private fun futureSlot() = AgreedTimeSlot(
        startIso = "2026-05-06T10:00:00Z",
        endIso = "2026-05-06T11:00:00Z", // after `now`
    )

    private fun plannedTask(taskId: UUID, title: String, vararg slots: AgreedTimeSlot) =
        AgreedPlanTask(taskId = taskId, title = title, slots = slots.toList())

    private fun task(id: UUID, title: String, status: TaskStatus) = BacklogTask(
        id = id,
        boardId = boardId,
        assigneeUserId = userId,
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = status,
        category = BacklogTaskCategory(UUID.randomUUID(), boardId, "General", CategoryColor.SKY),
        tags = emptySet(),
        sortKey = "m",
        createdAt = now.minusSeconds(30 * 24 * 3600),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = prevSessionId,
        relevantFrom = null,
    )
}
