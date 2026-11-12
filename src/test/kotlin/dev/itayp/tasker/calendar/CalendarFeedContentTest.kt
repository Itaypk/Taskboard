package dev.itayp.tasker.calendar

import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.PlannedTaskService
import dev.itayp.tasker.planning.PlanningSessionEntity
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CalendarFeedContentTest {

    private val planningSessionRepository: PlanningSessionRepository = mock()
    private val plannedTaskService: PlannedTaskService = mock()
    private val backlogTaskRepository: BacklogTaskRepository = mock()
    private val oneOffEventService: OneOffEventService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val content = CalendarFeedContent(
        planningSessionRepository, plannedTaskService, backlogTaskRepository, oneOffEventService, userSettingsService,
    )

    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val thisWeek = UUID.randomUUID()
    private val olderPlanOfThisWeek = UUID.randomUUID()

    private fun session(id: UUID, weekStart: LocalDate) = PlanningSessionEntity().apply {
        this.id = id
        this.userId = this@CalendarFeedContentTest.userId
        this.status = PlanningSessionStatus.COMPLETED
        this.weekStart = weekStart
        this.startedAt = now
    }

    private fun task(id: UUID, status: TaskStatus) = BacklogTaskEntity().apply {
        this.id = id
        this.status = status
    }

    private fun slot(start: String, end: String) = AgreedTimeSlot(start, end)

    private fun setUp(vararg tasks: Pair<AgreedPlanTask, TaskStatus?>) {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId, displayName = null, contextBlock = null, timeZone = "UTC",
                preferredLanguage = "en", gender = null, agentDescription = null, planningCron = null,
                weekStartDay = null, autoArchiveDays = null,
            ),
        )
        val week = LocalDate.of(2026, 11, 9)
        whenever(
            planningSessionRepository.findAllByUserIdAndStatusAndWeekStartGreaterThanEqualOrderByWeekStartDescStartedAtDesc(
                eq(userId), eq(PlanningSessionStatus.COMPLETED), any(),
            ),
        ).thenReturn(listOf(session(thisWeek, week), session(olderPlanOfThisWeek, week)))
        whenever(plannedTaskService.findForSession(userId, thisWeek)).thenReturn(tasks.map { it.first })
        whenever(backlogTaskRepository.findAllById(any<Iterable<UUID>>())).thenReturn(
            tasks.mapNotNull { (planned, status) -> status?.let { task(planned.taskId, it) } },
        )
        whenever(oneOffEventService.listForWeek(eq(userId), any(), any())).thenReturn(emptyList())
    }

    @Test
    fun `keeps only the latest plan of each week`() {
        val planned = AgreedPlanTask(UUID.randomUUID(), "A", listOf(slot("2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z")))
        setUp(planned to TaskStatus.TODO)

        val result = content.collect(userId, now.minusSeconds(14 * 86400), now)

        assertEquals(listOf(planned), result.planTasks)
    }

    @Test
    fun `drops slots that ended before the window`() {
        val planned = AgreedPlanTask(
            UUID.randomUUID(), "A",
            listOf(slot("2026-11-10T09:00:00Z", "2026-11-10T10:00:00Z"), slot("2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z")),
        )
        setUp(planned to TaskStatus.TODO)

        val result = content.collect(userId, now, now)

        assertEquals(listOf("2026-11-13T09:00:00Z"), result.planTasks.single().slots.map { it.startIso })
    }

    @Test
    fun `a task done early keeps its past blocks but loses its upcoming ones`() {
        val planned = AgreedPlanTask(
            UUID.randomUUID(), "A",
            listOf(slot("2026-11-11T09:00:00Z", "2026-11-11T10:00:00Z"), slot("2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z")),
        )
        setUp(planned to TaskStatus.DONE)

        val result = content.collect(userId, now.minusSeconds(14 * 86400), now)

        assertEquals(listOf("2026-11-11T09:00:00Z"), result.planTasks.single().slots.map { it.startIso })
    }

    @Test
    fun `a deleted task loses its upcoming blocks too`() {
        val planned = AgreedPlanTask(UUID.randomUUID(), "A", listOf(slot("2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z")))
        setUp(planned to null)

        assertEquals(emptyList<AgreedPlanTask>(), content.collect(userId, now, now).planTasks)
    }
}
