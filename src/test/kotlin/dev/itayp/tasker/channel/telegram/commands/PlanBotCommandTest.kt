package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.planning.PlanningSession
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.support.StaticMessageSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PlanBotCommandTest {

    @Mock private lateinit var orchestrator: WeeklyPlanningOrchestrator
    @Mock private lateinit var planningSessionService: PlanningSessionService
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var channel: TelegramConversationChannel
    @Mock private lateinit var sessionRegistry: TelegramSessionRegistry

    private val planConfirmationRegistry = PlanConfirmationRegistry()

    // Fixed Wednesday 2026-05-13 UTC
    private val clock = Clock.fixed(Instant.parse("2026-05-13T10:00:00Z"), ZoneOffset.UTC)

    private val command by lazy {
        PlanBotCommand(
            orchestrator,
            planningSessionService,
            planConfirmationRegistry,
            userSettingsService,
            StaticMessageSource().also { src ->
                src.addMessage("planning.confirm.active.prompt", Locale.ENGLISH, "Session in progress")
                src.addMessage("planning.confirm.active.continue", Locale.ENGLISH, "Continue")
                src.addMessage("planning.confirm.active.abandon", Locale.ENGLISH, "Abandon")
                src.addMessage("planning.confirm.completed.prompt", Locale.ENGLISH, "Current plan: {0}")
                src.addMessage("planning.confirm.completed.keep", Locale.ENGLISH, "Keep")
                src.addMessage("planning.confirm.completed.revise", Locale.ENGLISH, "Revise")
                src.addMessage("planning.confirm.completed.start_over", Locale.ENGLISH, "Start over")
                src.addMessage("planning.choose_week.prompt", Locale.ENGLISH, "Which week?")
                src.addMessage("planning.choose_week.this_week", Locale.ENGLISH, "This week ({0} – {1})")
                src.addMessage("planning.choose_week.next_week", Locale.ENGLISH, "Next week ({0} – {1})")
            },
            clock,
        )
    }

    private val userId = UUID.randomUUID()
    private val chatId = 42L

    private fun context() = BotCommandContext(userId, chatId, "", channel, sessionRegistry)

    @BeforeEach
    fun setUp() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
    }

    private fun stubUserSettings(weekStartDay: String? = "MONDAY", timeZone: String = "UTC") {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId,
                displayName = null,
                contextBlock = null,
                timeZone = timeZone,
                preferredLanguage = "en-US",
                calendarInviteEmail = false,
                gender = null,
                agentDescription = null,
                planningCron = null,
                weekStartDay = weekStartDay,
                autoArchiveDays = null,
            )
        )
    }

    @Test
    fun `sends active-session choice and registers confirmation when session is in progress`() {
        val sessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(sessionId)
        whenever(orchestrator.phase(sessionId)).thenReturn(WeeklyPlanningOrchestrator.Phase.CONVERSING)
        whenever(planningSessionService.findById(userId, sessionId)).thenReturn(
            PlanningSession(
                id = sessionId,
                userId = userId,
                conversationId = null,
                status = PlanningSessionStatus.ACTIVE,
                startedAt = Instant.parse("2026-05-11T10:00:00Z"),
                weekStart = LocalDate.parse("2026-05-11"),
                endedAt = null,
                summary = null,
            )
        )

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        assertEquals("Session in progress", msg.prompt)
        assertEquals(listOf(
            ChoiceOption(PlanConfirmationRegistry.OPTION_KEEP, "Continue"),
            ChoiceOption(PlanConfirmationRegistry.OPTION_THIS_WEEK, "Abandon"),
        ), msg.options)

        val pending = planConfirmationRegistry.get(chatId)
        assertNotNull(pending)
        assertEquals(userId, pending.userId)
        assertEquals(sessionId, pending.existingSessionId)
        assertEquals(LocalDate.parse("2026-05-11"), pending.replanWeekStart)

        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `shows completed plan summary and registers confirmation when completed plan exists`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        val completedPlan = PlanningSession(
            id = UUID.randomUUID(),
            userId = userId,
            conversationId = null,
            status = PlanningSessionStatus.COMPLETED,
            startedAt = Instant.parse("2026-05-11T10:00:00Z"),
            weekStart = LocalDate.parse("2026-05-11"),
            endedAt = null,
            summary = "Week 20 plan summary",
        )
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(completedPlan)

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        assertEquals("Current plan: Week 20 plan summary", msg.prompt)
        assertEquals(listOf(
            ChoiceOption(PlanConfirmationRegistry.OPTION_KEEP, "Keep"),
            ChoiceOption(PlanConfirmationRegistry.OPTION_REVISE, "Revise"),
            ChoiceOption(PlanConfirmationRegistry.OPTION_THIS_WEEK, "Start over"),
        ), msg.options)

        val pending = planConfirmationRegistry.get(chatId)
        assertNotNull(pending)
        assertEquals(userId, pending.userId)
        assertNull(pending.existingSessionId)
        assertEquals(LocalDate.parse("2026-05-11"), pending.replanWeekStart)
        assertEquals(completedPlan.id, pending.revisableSessionId)

        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `prompts week picker when no existing plan or active session`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        stubUserSettings()

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        assertEquals("Which week?", msg.prompt)
        // Wednesday 2026-05-13 with Monday week-start → this week = 2026-05-11, next = 2026-05-18
        assertEquals(PlanConfirmationRegistry.OPTION_THIS_WEEK, msg.options[0].id)
        assertEquals(PlanConfirmationRegistry.OPTION_NEXT_WEEK, msg.options[1].id)

        val pending = planConfirmationRegistry.get(chatId)
        assertNotNull(pending)
        assertNull(pending.existingSessionId)
        assertNull(pending.replanWeekStart)

        verify(orchestrator, never()).start(any(), any(), any())
    }

    @Test
    fun `prompts week picker when completed plan exists but has no summary`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        val completedPlan = PlanningSession(
            id = UUID.randomUUID(),
            userId = userId,
            conversationId = null,
            status = PlanningSessionStatus.COMPLETED,
            startedAt = Instant.parse("2026-05-11T10:00:00Z"),
            weekStart = LocalDate.parse("2026-05-11"),
            endedAt = null,
            summary = null,
        )
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(completedPlan)
        stubUserSettings()

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        // No summary → fall through to the week picker rather than the keep/replan prompt
        assertEquals("Which week?", msg.prompt)

        val pending = planConfirmationRegistry.get(chatId)
        assertNotNull(pending)
        assertNull(pending.replanWeekStart)
    }

    @Test
    fun `week picker uses user's weekStartDay anchored on user's local today`() {
        // Clock is fixed at 2026-05-13 10:00 UTC. In America/New_York that's 2026-05-13 06:00 local.
        // With weekStartDay=SUNDAY, the most recent Sunday on/before local-today (Wed 2026-05-13)
        // is 2026-05-10; next week start is 2026-05-17.
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        stubUserSettings(weekStartDay = "SUNDAY", timeZone = "America/New_York")

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        // Labels are formatted with the medium-style date formatter; rather than asserting
        // the exact label string we assert the option IDs are present and rely on the
        // resolver call signature being the same one TelegramChannel uses to start the session.
        assertEquals(PlanConfirmationRegistry.OPTION_THIS_WEEK, msg.options[0].id)
        assertEquals(PlanConfirmationRegistry.OPTION_NEXT_WEEK, msg.options[1].id)
        // Sanity check the label contains the expected anchored dates.
        assertTrue(msg.options[0].label.contains("May 10")) // this week start = Sun May 10 (user TZ)
        assertTrue(msg.options[1].label.contains("May 17")) // next week start = Sun May 17 (user TZ)
    }

    @Test
    fun `prompts week picker when active session is stale (no orchestrator phase)`() {
        val staleSessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(staleSessionId)
        whenever(orchestrator.phase(staleSessionId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        stubUserSettings()

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        assertEquals("Which week?", msg.prompt)

        verify(orchestrator, never()).start(any(), any(), any())
    }
}
