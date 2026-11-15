package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.HtmlMessageFormatter
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.planning.PlanRecapRenderer
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.Mockito.lenient
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class CurrentBotCommandTest {

    @Mock private lateinit var planningSessionService: PlanningSessionService
    @Mock private lateinit var recapRenderer: PlanRecapRenderer
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var channel: TelegramConversationChannel
    @Mock private lateinit var sessionRegistry: TelegramSessionRegistry

    private val command by lazy {
        CurrentBotCommand(
            planningSessionService,
            recapRenderer,
            userSettingsService,
            StaticMessageSource().also { src ->
                src.addMessage("command.current.empty", Locale.ENGLISH, "No plan yet.")
                src.addMessage("command.current.status.active", Locale.ENGLISH, "Current plan (in progress)")
                src.addMessage("command.current.status.completed", Locale.ENGLISH, "Current plan")
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
        lenient().`when`(channel.formatter).thenReturn(HtmlMessageFormatter)
    }

    @Test
    fun `sends empty message when there is no current plan`() {
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)

        command.handle(context())

        assertEquals("No plan yet.", sentText())
    }

    @Test
    fun `renders the header, the tasks and then the notes`() {
        val plan = plan(PlanningSessionStatus.COMPLETED)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(plan)
        whenever(recapRenderer.renderTasks(plan, HtmlMessageFormatter, Locale.ENGLISH)).thenReturn("▫️ Write blog post")
        whenever(recapRenderer.renderNotes(plan, HtmlMessageFormatter, Locale.ENGLISH)).thenReturn("<b>Notes</b>\nHeavy week.")

        command.handle(context())

        val text = sentText()
        assertTrue(text.startsWith("<b>Current plan</b>\n<i>"), "expected status header, got: $text")
        assertTrue(text.endsWith("▫️ Write blog post\n\n<b>Notes</b>\nHeavy week."), "got: $text")
    }

    @Test
    fun `renders in-progress label and the empty-tasks line, without a notes block`() {
        val plan = plan(PlanningSessionStatus.ACTIVE)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(plan)
        whenever(recapRenderer.renderTasks(plan, HtmlMessageFormatter, Locale.ENGLISH)).thenReturn(null)
        whenever(recapRenderer.renderNotes(plan, HtmlMessageFormatter, Locale.ENGLISH)).thenReturn(null)

        command.handle(context())

        val text = sentText()
        assertTrue(text.contains("<b>Current plan (in progress)</b>"))
        assertTrue(text.endsWith("No tasks scheduled."), "got: $text")
        assertFalse(text.contains("null"))
    }

    private fun sentText(): String {
        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        return (captor.firstValue as ChannelMessage.Text).text
    }

    private fun plan(status: PlanningSessionStatus) = PlanningSession(
        id = UUID.randomUUID(),
        userId = userId,
        conversationId = null,
        status = status,
        startedAt = Instant.parse("2026-05-14T10:00:00Z"),
        weekStart = LocalDate.parse("2026-05-11"),
        endedAt = null,
        weekNote = null,
    )
}
