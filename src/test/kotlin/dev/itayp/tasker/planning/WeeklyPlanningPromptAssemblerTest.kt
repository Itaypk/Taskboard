package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.BoardService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused on the "What changed since the last session" block: the change feed is historical, so the
 * assembler is the layer that must keep it from offering work the planner can't actually schedule.
 */
@ExtendWith(MockitoExtension::class)
class WeeklyPlanningPromptAssemblerTest {

    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var plannerTaskSelector: PlannerTaskSelector
    @Mock private lateinit var planningSessionService: PlanningSessionService
    @Mock private lateinit var calendarWindowProvider: CalendarWindowProvider
    @Mock private lateinit var categoryService: BacklogTaskCategoryService
    @Mock private lateinit var tagService: BacklogTaskTagService
    @Mock private lateinit var boardService: BoardService
    @Mock private lateinit var aiAccessService: AiAccessService
    @Mock private lateinit var inviteDeliveryResolver: InviteDeliveryResolver

    private val today = LocalDate.parse("2026-05-01")
    private val weekStart = LocalDate.parse("2026-05-04")
    private val clock: Clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private val assembler by lazy {
        WeeklyPlanningPromptAssembler(
            PromptTemplateLoader(), userSettingsService, plannerTaskSelector, planningSessionService,
            calendarWindowProvider, categoryService, tagService, boardService, aiAccessService,
            inviteDeliveryResolver, clock,
        )
    }

    private val userId: UUID = UUID.randomUUID()

    @BeforeEach
    fun stubCollaborators() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings())
        whenever(plannerTaskSelector.select(any(), any(), any(), any(), any(), any()))
            .thenReturn(PlannerTaskSelection(emptyList(), emptyList()))
        lenient().`when`(planningSessionService.findPreviousSummarizableSession(any(), any())).thenReturn(null)
        whenever(aiAccessService.aiAllowedBoardIds(userId)).thenReturn(emptySet())
        whenever(boardService.listBoardsForUser(userId)).thenReturn(emptyList())
        whenever(calendarWindowProvider.describeWindow(any(), any(), any())).thenReturn("no calendar")
        whenever(inviteDeliveryResolver.describeDeliveryMethods(userId)).thenReturn("none")
    }

    @Test
    fun `newly added and reopened are dropped when the planner can no longer schedule them`() {
        val schedulable = ChangedTask(UUID.randomUUID(), "Still on the board")
        val futureDated = ChangedTask(UUID.randomUUID(), "Not relevant yet")
        val reopenedGone = ChangedTask(UUID.randomUUID(), "Reopened then hidden")
        whenever(planningSessionService.diffSincePreviousSession(userId, weekStart)).thenReturn(
            summary(created = listOf(schedulable, futureDated), reopened = listOf(reopenedGone), events = 3),
        )
        // Only the first task survives the live visibility check.
        whenever(plannerTaskSelector.visibleTaskIds(any(), any(), any())).thenReturn(setOf(schedulable.taskId))

        val prompt = assemble()

        assertTrue(prompt.contains("Newly added: Still on the board"))
        assertFalse(prompt.contains("Not relevant yet"))
        assertFalse(prompt.contains("Reopened then hidden"))
    }

    @Test
    fun `completed and removed are reported verbatim, without a live lookup`() {
        val done = ChangedTask(UUID.randomUUID(), "Finished thing")
        val archived = ChangedTask(UUID.randomUUID(), "Archived thing")
        whenever(planningSessionService.diffSincePreviousSession(userId, weekStart)).thenReturn(
            summary(completed = listOf(done), removed = listOf(archived), events = 2),
        )
        whenever(plannerTaskSelector.visibleTaskIds(any(), any(), any())).thenReturn(emptySet())

        val prompt = assemble()

        assertTrue(prompt.contains("Completed: Finished thing"))
        assertTrue(prompt.contains("Removed from the backlog: Archived thing"))
    }

    @Test
    fun `a diff whose every entry is filtered out reads as no changes`() {
        val gone = ChangedTask(UUID.randomUUID(), "Ghost task")
        whenever(planningSessionService.diffSincePreviousSession(userId, weekStart)).thenReturn(
            summary(created = listOf(gone), events = 1),
        )
        whenever(plannerTaskSelector.visibleTaskIds(any(), any(), any())).thenReturn(emptySet())

        val prompt = assemble()

        assertFalse(prompt.contains("Ghost task"))
        assertTrue(prompt.contains("No backlog changes recorded since the last session."))
    }

    private fun assemble(): String =
        assembler.assembleSystemPrompt(userId, "a normal week", weekStart, HtmlMessageFormatter)

    private fun summary(
        created: List<ChangedTask> = emptyList(),
        completed: List<ChangedTask> = emptyList(),
        reopened: List<ChangedTask> = emptyList(),
        removed: List<ChangedTask> = emptyList(),
        events: Int,
    ) = TaskChangeSummary(
        createdDuringWindow = created,
        completed = completed,
        completedFromBacklog = completed,
        completedAddedDuringWindow = emptyList(),
        reopened = reopened,
        removed = removed,
        deleted = emptyList(),
        totalEvents = events,
    )

    private fun settings() = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = "UTC",
        preferredLanguage = "en",
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = "MONDAY",
        autoArchiveDays = null,
    )
}
