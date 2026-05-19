package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlanningSessionEntity
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class CurrentBotCommandTest {

    @Mock private lateinit var planningSessionService: PlanningSessionService
    @Mock private lateinit var backlogTaskService: BacklogTaskService
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var channel: TelegramConversationChannel
    @Mock private lateinit var sessionRegistry: TelegramSessionRegistry

    private val command by lazy {
        CurrentBotCommand(
            planningSessionService,
            backlogTaskService,
            userSettingsService,
            StaticMessageSource().also { src ->
                src.addMessage("command.current.empty", Locale.ENGLISH, "No plan yet.")
                src.addMessage("command.current.status.active", Locale.ENGLISH, "Current plan (in progress)")
                src.addMessage("command.current.status.completed", Locale.ENGLISH, "Current plan")
                src.addMessage("command.current.summary.label", Locale.ENGLISH, "Summary")
                src.addMessage("command.current.summary.empty", Locale.ENGLISH, "No summary recorded.")
                src.addMessage("command.current.tasks.header", Locale.ENGLISH, "Tasks ({0})")
                src.addMessage("command.current.tasks.empty", Locale.ENGLISH, "No tasks scheduled.")
            },
        )
    }

    private val userId = UUID.randomUUID()
    private val chatId = 7L

    private fun context() = BotCommandContext(userId, chatId, "", channel, sessionRegistry)

    @BeforeEach
    fun setUp() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
    }

    private fun stubUserSettings() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettingsEntity().apply { this.userId = this@CurrentBotCommandTest.userId }
        )
    }

    @Test
    fun `sends empty message when there is no current plan`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Text
        assertEquals("No plan yet.", msg.text)
    }

    @Test
    fun `renders summary and tasks for a completed plan`() {
        stubUserSettings()
        val sessionId = UUID.randomUUID()
        val plan = PlanningSessionEntity().apply {
            id = sessionId
            this.userId = this@CurrentBotCommandTest.userId
            status = PlanningSessionStatus.COMPLETED
            startedAt = Instant.parse("2026-05-14T10:00:00Z")
            summary = "Wrap up the launch & ship docs"
        }
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(plan)
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId))
            .thenReturn(
                listOf(
                    task("Write blog post", TaskStatus.TODO),
                    task("Email customers", TaskStatus.DONE),
                )
            )

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val text = (captor.firstValue as ChannelMessage.Text).text

        assertTrue(text.contains("<b>Current plan</b>"), "expected status header, got: $text")
        assertTrue(text.contains("Wrap up the launch &amp; ship docs"), "expected escaped summary, got: $text")
        assertTrue(text.contains("Tasks (2)"))
        assertTrue(text.contains("▫️ Write blog post"))
        assertTrue(text.contains("✅ <s>Email customers</s>"))
    }

    @Test
    fun `renders in-progress label when plan is active`() {
        stubUserSettings()
        val sessionId = UUID.randomUUID()
        val plan = PlanningSessionEntity().apply {
            id = sessionId
            this.userId = this@CurrentBotCommandTest.userId
            status = PlanningSessionStatus.ACTIVE
            startedAt = Instant.parse("2026-05-14T10:00:00Z")
            summary = null
        }
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(plan)
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId)).thenReturn(emptyList())

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val text = (captor.firstValue as ChannelMessage.Text).text

        assertTrue(text.contains("<b>Current plan (in progress)</b>"))
        assertTrue(text.contains("No summary recorded."))
        assertTrue(text.contains("No tasks scheduled."))
    }

    @Test
    fun `escapes HTML in task titles`() {
        stubUserSettings()
        val sessionId = UUID.randomUUID()
        val plan = PlanningSessionEntity().apply {
            id = sessionId
            this.userId = this@CurrentBotCommandTest.userId
            status = PlanningSessionStatus.COMPLETED
            startedAt = Instant.parse("2026-05-14T10:00:00Z")
            summary = "ok"
        }
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(plan)
        whenever(backlogTaskService.getTasksScheduledInSession(userId, sessionId))
            .thenReturn(listOf(task("<script>alert(1)</script>", TaskStatus.TODO)))

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val text = (captor.firstValue as ChannelMessage.Text).text

        assertTrue(text.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(!text.contains("<script>"))
    }

    private fun task(title: String, status: TaskStatus): BacklogTask {
        val now = Instant.parse("2026-05-14T10:00:00Z")
        return BacklogTask(
            id = UUID.randomUUID(),
            userId = userId,
            title = title,
            description = null,
            url = null,
            priority = null,
            deadline = null,
            estimatedMinutes = null,
            status = status,
            category = BacklogTaskCategory(
                id = UUID.randomUUID(),
                userId = userId,
                label = "Work",
                swatchId = CategoryColor.SKY,
            ),
            tags = emptySet(),
            sortKey = "a",
            createdAt = now,
            updatedAt = null,
            rescheduleCount = 0,
            lastScheduledInSessionId = null,
        )
    }
}
