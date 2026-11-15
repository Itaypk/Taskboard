package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.Mockito.lenient
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class PlanRecapRendererTest {

    @Mock private lateinit var plannedTaskService: PlannedTaskService
    @Mock private lateinit var backlogTaskService: BacklogTaskService
    @Mock private lateinit var userSettingsService: UserSettingsService

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val messages = StaticMessageSource().also {
        it.addMessage("plan.recap.empty", Locale.ENGLISH, "No tasks scheduled.")
        it.addMessage("plan.recap.notes", Locale.ENGLISH, "Notes")
    }

    private val renderer by lazy {
        PlanRecapRenderer(plannedTaskService, backlogTaskService, userSettingsService, messages)
    }

    @BeforeEach
    fun setUp() {
        lenient().`when`(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        lenient().`when`(userSettingsService.getOrCreate(userId)).thenReturn(settings("Asia/Jerusalem"))
    }

    @Test
    fun `renders live status and title with slots in the user's zone`() {
        val openId = UUID.randomUUID()
        val doneId = UUID.randomUUID()
        val goneId = UUID.randomUUID()
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(listOf(
            planned(openId, "Old title", AgreedTimeSlot("2026-11-01T08:00:00Z", "2026-11-01T09:30:00Z")),
            planned(doneId, "Fix the lamp", AgreedTimeSlot("2026-11-02T15:00:00Z", "2026-11-02T15:30:00Z")),
            planned(goneId, "Since deleted"),
        ))
        whenever(backlogTaskService.findTasksAcrossBoards(any(), any())).thenReturn(listOf(
            task(openId, "Fix the <door> locks", TaskStatus.TODO),
            task(doneId, "Fix the lamp", TaskStatus.DONE),
        ))

        val text = renderer.renderTasks(session(), HtmlMessageFormatter, Locale.ENGLISH)

        assertEquals(
            "▫️ Fix the &lt;door&gt; locks — Sun 10:00 AM–11:30 AM\n" +
                "✅ Fix the lamp — Mon 5:00 PM–5:30 PM\n" +
                "▫️ Since deleted",
            text?.replace(' ', ' '),
        )
    }

    @Test
    fun `renders the week note and dated revision notes under a label`() {
        val session = session(
            weekNote = "Heavy week & travelling.",
            revisionNotes = listOf(RevisionNote(Instant.parse("2026-11-03T22:30:00Z"), "Moved the dentist.")),
        )

        val text = renderer.renderNotes(session, HtmlMessageFormatter, Locale.ENGLISH)

        // 22:30 UTC is already Nov 4 in Jerusalem.
        assertEquals("<b>Notes</b>\nHeavy week &amp; travelling.\n• Nov 4, 2026: Moved the dentist.", text)
    }

    @Test
    fun `render falls back to the empty line and omits notes when there are none`() {
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(emptyList())

        assertNull(renderer.renderNotes(session(), HtmlMessageFormatter, Locale.ENGLISH))
        assertEquals("No tasks scheduled.", renderer.render(session(), HtmlMessageFormatter))
    }

    private fun session(weekNote: String? = null, revisionNotes: List<RevisionNote> = emptyList()) = PlanningSession(
        id = sessionId,
        userId = userId,
        conversationId = null,
        status = PlanningSessionStatus.COMPLETED,
        startedAt = Instant.parse("2026-10-31T10:00:00Z"),
        weekStart = LocalDate.parse("2026-11-01"),
        endedAt = Instant.parse("2026-10-31T10:20:00Z"),
        weekNote = weekNote,
        revisionNotes = revisionNotes,
    )

    private fun planned(id: UUID, title: String, vararg slots: AgreedTimeSlot) =
        AgreedPlanTask(taskId = id, title = title, slots = slots.toList())

    private fun settings(zone: String) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = zone,
        preferredLanguage = "en",
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = "SUNDAY",
        autoArchiveDays = null,
    )

    private fun task(id: UUID, title: String, status: TaskStatus) = BacklogTask(
        id = id,
        boardId = userId,
        assigneeUserId = null,
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = status,
        category = BacklogTaskCategory(id = UUID.randomUUID(), boardId = userId, label = "Home", swatchId = CategoryColor.SKY),
        tags = emptySet(),
        sortKey = "a",
        createdAt = Instant.parse("2026-10-01T10:00:00Z"),
        updatedAt = null,
        rescheduleCount = 0,
        lastScheduledInSessionId = null,
        relevantFrom = null,
    )
}
