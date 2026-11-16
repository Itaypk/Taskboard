package dev.itayp.tasker.planning

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.SortKeyGenerator
import dev.itayp.tasker.service.UserSettingsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectPlanServiceTest {

    @Mock lateinit var planningSessionService: PlanningSessionService
    @Mock lateinit var plannedTaskService: PlannedTaskService
    @Mock lateinit var planFinalizationService: PlanFinalizationService
    @Mock lateinit var backlogTaskService: BacklogTaskService
    @Mock lateinit var orchestrator: WeeklyPlanningOrchestrator
    @Mock lateinit var userSettingsService: UserSettingsService

    private lateinit var service: DirectPlanService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val hiddenTaskId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000010")

    /** A Monday, the current week in every test. Zone Asia/Jerusalem (+02:00 in November). */
    private val weekStart = LocalDate.parse("2026-11-16")

    @BeforeEach
    fun setUp() {
        service = DirectPlanService(
            planningSessionService, plannedTaskService, planFinalizationService,
            backlogTaskService, orchestrator, userSettingsService,
        )
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId, displayName = null, contextBlock = null, timeZone = "Asia/Jerusalem",
                preferredLanguage = "en-US", gender = null, agentDescription = null, planningCron = null,
                weekStartDay = "MONDAY", autoArchiveDays = null,
            )
        )
        whenever(planningSessionService.currentWeekStart(userId)).thenReturn(weekStart)
    }

    private fun aTask(id: UUID, title: String, status: TaskStatus = TaskStatus.TODO, hidden: Boolean = false) =
        BacklogTask(
            id = id, boardId = boardId, assigneeUserId = null, title = title, description = null, url = null,
            priority = null, deadline = null, estimatedMinutes = null, status = status,
            category = BacklogTaskCategory(UUID.randomUUID(), boardId, "Work", CategoryColor.SUNSHINE),
            tags = emptySet(), sortKey = SortKeyGenerator.INITIAL, createdAt = Instant.EPOCH, updatedAt = null,
            rescheduleCount = 0, lastScheduledInSessionId = null, relevantFrom = null, hiddenFromAssistant = hidden,
        )

    private fun session(status: PlanningSessionStatus = PlanningSessionStatus.COMPLETED) = PlanningSession(
        id = sessionId, userId = userId, conversationId = null, status = status,
        startedAt = Instant.EPOCH, weekStart = weekStart, endedAt = Instant.EPOCH, weekNote = null,
    )

    private fun slot(start: String, end: String) = DirectPlanSlot(OffsetDateTime.parse(start), OffsetDateTime.parse(end))

    private fun tuesdayMorning(id: UUID = taskId) =
        DirectPlanTask(id, listOf(slot("2026-11-17T09:00:00+02:00", "2026-11-17T10:00:00+02:00")))

    private fun stubTasks(vararg tasks: BacklogTask) {
        whenever(backlogTaskService.findTasksAcrossBoards(eq(userId), any())).thenReturn(tasks.toList())
    }

    private fun stubExistingPlan(vararg planned: AgreedPlanTask) {
        whenever(planningSessionService.findPlanForWeek(userId, weekStart)).thenReturn(session())
        whenever(planningSessionService.findSessionForWeek(userId, weekStart)).thenReturn(session())
        whenever(planningSessionService.findById(userId, sessionId)).thenReturn(session())
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(planned.toList())
    }

    @Test
    fun `a week's first plan is finalized through complete, with the task's own title`() {
        stubTasks(aTask(taskId, "Renew passport"))
        whenever(planningSessionService.openSessionForDirectPlan(userId, weekStart))
            .thenReturn(session(PlanningSessionStatus.ACTIVE))
        whenever(planningSessionService.findById(userId, sessionId)).thenReturn(session())

        val result = service.submit(userId, weekStart, listOf(tuesdayMorning()), note = "Light week")

        assertThat(result).isInstanceOf(DirectPlanResult.Applied::class.java)
        assertThat((result as DirectPlanResult.Applied).created).isTrue()
        val plan = argumentCaptor<AgreedPlan>()
        verify(planFinalizationService).complete(eq(userId), eq(sessionId), plan.capture())
        assertThat(plan.firstValue.weekNote).isEqualTo("Light week")
        assertThat(plan.firstValue.tasks.single().title).isEqualTo("Renew passport")
        assertThat(plan.firstValue.tasks.single().slots.single().startIso).isEqualTo("2026-11-17T09:00+02:00")
    }

    @Test
    fun `a later submit revises, keeps unlisted hidden tasks and reuses stored slot strings`() {
        val storedSlot = AgreedTimeSlot("2026-11-17T07:00:00Z", "2026-11-17T08:00:00Z")
        val hiddenSlot = AgreedTimeSlot("2026-11-18T07:00:00Z", "2026-11-18T08:00:00Z")
        stubExistingPlan(
            AgreedPlanTask(taskId, "Renew passport", listOf(storedSlot)),
            AgreedPlanTask(hiddenTaskId, "Therapy", listOf(hiddenSlot)),
        )
        stubTasks(aTask(taskId, "Renew passport"), aTask(hiddenTaskId, "Therapy", hidden = true))

        // Same instant as the stored slot, written in another offset.
        val result = service.submit(userId, weekStart, listOf(tuesdayMorning()), note = "Moved nothing")

        assertThat((result as DirectPlanResult.Applied).keptHiddenTasks).isEqualTo(1)
        val plan = argumentCaptor<AgreedPlan>()
        verify(planFinalizationService).revisePlan(eq(userId), eq(sessionId), plan.capture())
        assertThat(plan.firstValue.revisionNote).isEqualTo("Moved nothing")
        assertThat(plan.firstValue.tasks.map { it.taskId }).containsExactly(taskId, hiddenTaskId)
        assertThat(plan.firstValue.tasks.first().slots.single()).isEqualTo(storedSlot)
        assertThat(plan.firstValue.tasks.last().slots.single()).isEqualTo(hiddenSlot)
        verify(planFinalizationService, never()).complete(any(), any(), any())
    }

    @Test
    fun `an unreachable task is rejected and nothing is written`() {
        stubTasks()

        val result = service.submit(userId, weekStart, listOf(tuesdayMorning()), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.UNPROCESSABLE)
        assertThat(result.detail).contains(taskId.toString())
        verify(planningSessionService, never()).openSessionForDirectPlan(any(), any())
    }

    @Test
    fun `a week start on the wrong weekday is invalid and names the right one`() {
        val result = service.submit(userId, weekStart.plusDays(1), emptyList(), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.INVALID)
        assertThat(result.detail).contains("monday").contains(weekStart.toString())
    }

    @Test
    fun `a past week is rejected`() {
        val result = service.submit(userId, weekStart.minusDays(7), emptyList(), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.UNPROCESSABLE)
    }

    @Test
    fun `a slot starting outside the week is invalid`() {
        stubTasks(aTask(taskId, "Renew passport"))
        // Monday 00:30 in Jerusalem is Sunday 22:30 UTC: still inside. Sunday 23:30 local is not.
        val outside = DirectPlanTask(taskId, listOf(slot("2026-11-15T23:30:00+02:00", "2026-11-16T00:30:00+02:00")))

        val result = service.submit(userId, weekStart, listOf(outside), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.INVALID)
        assertThat(result.detail).contains("outside the week").contains("Asia/Jerusalem")
    }

    @Test
    fun `a slot ending before it starts is invalid`() {
        stubTasks(aTask(taskId, "Renew passport"))
        val backwards = DirectPlanTask(taskId, listOf(slot("2026-11-17T10:00:00+02:00", "2026-11-17T09:00:00+02:00")))

        val result = service.submit(userId, weekStart, listOf(backwards), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.INVALID)
    }

    @Test
    fun `a live in-app planning conversation for the week is a conflict`() {
        stubExistingPlan()
        whenever(orchestrator.hasLiveConversation(sessionId)).thenReturn(true)

        val result = service.submit(userId, weekStart, emptyList(), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.CONFLICT)
        verify(planFinalizationService, never()).revisePlan(any(), any(), any())
    }

    @Test
    fun `a done task can stay as it was but can't be added or moved`() {
        val storedSlot = AgreedTimeSlot("2026-11-17T09:00:00+02:00", "2026-11-17T10:00:00+02:00")
        stubExistingPlan(AgreedPlanTask(taskId, "Renew passport", listOf(storedSlot)))
        stubTasks(aTask(taskId, "Renew passport", status = TaskStatus.DONE))

        val unchanged = service.submit(userId, weekStart, listOf(tuesdayMorning()), note = null)
        assertThat(unchanged).isInstanceOf(DirectPlanResult.Applied::class.java)

        val moved = DirectPlanTask(taskId, listOf(slot("2026-11-19T09:00:00+02:00", "2026-11-19T10:00:00+02:00")))
        val result = service.submit(userId, weekStart, listOf(moved), note = null)
        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.UNPROCESSABLE)
        assertThat(result.detail).contains("can't be rescheduled")
    }

    @Test
    fun `a task listed twice is invalid`() {
        val result = service.submit(userId, weekStart, listOf(tuesdayMorning(), tuesdayMorning()), note = null)

        assertThat((result as DirectPlanResult.Rejected).kind).isEqualTo(DirectPlanResult.Kind.INVALID)
    }

    @Test
    fun `a failed first finalization abandons the session it opened`() {
        stubTasks(aTask(taskId, "Renew passport"))
        whenever(planningSessionService.openSessionForDirectPlan(userId, weekStart))
            .thenReturn(session(PlanningSessionStatus.ACTIVE))
        whenever(planFinalizationService.complete(any(), any(), any())).thenThrow(IllegalStateException("boom"))

        runCatching { service.submit(userId, weekStart, listOf(tuesdayMorning()), note = null) }

        verify(planningSessionService).abandonSession(userId, sessionId)
    }
}
