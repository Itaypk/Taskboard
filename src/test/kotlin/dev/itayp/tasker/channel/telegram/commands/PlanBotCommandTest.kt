package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.telegram.TelegramConversationChannel
import dev.itayp.tasker.channel.telegram.TelegramSessionRegistry
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.planning.PlanningSessionEntity
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class PlanBotCommandTest {

    @Mock private lateinit var orchestrator: WeeklyPlanningOrchestrator
    @Mock private lateinit var planningSessionService: PlanningSessionService
    @Mock private lateinit var userSettingsService: UserSettingsService
    @Mock private lateinit var channel: TelegramConversationChannel
    @Mock private lateinit var sessionRegistry: TelegramSessionRegistry

    private val planConfirmationRegistry = PlanConfirmationRegistry()

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
                src.addMessage("planning.confirm.completed.new", Locale.ENGLISH, "Plan again")
            },
        )
    }

    private val userId = UUID.randomUUID()
    private val chatId = 42L

    private fun context() = BotCommandContext(userId, chatId, "", channel, sessionRegistry)

    @BeforeEach
    fun setUp() {
        val settings = UserSettingsEntity().apply { preferredLanguage = "en" }
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings)
    }

    @Test
    fun `sends active-session choice and registers confirmation when session is in progress`() {
        val sessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(sessionId)
        whenever(orchestrator.phase(sessionId)).thenReturn(WeeklyPlanningOrchestrator.Phase.CONVERSING)

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        assertEquals("Session in progress", msg.prompt)
        assertEquals(listOf(
            ChoiceOption(PlanConfirmationRegistry.OPTION_KEEP, "Continue"),
            ChoiceOption(PlanConfirmationRegistry.OPTION_NEW, "Abandon"),
        ), msg.options)

        val pending = planConfirmationRegistry.get(chatId)
        assertNotNull(pending)
        assertEquals(userId, pending.userId)
        assertEquals(sessionId, pending.existingSessionId)

        verify(orchestrator, never()).start(any(), any())
    }

    @Test
    fun `shows completed plan summary and registers confirmation when completed plan exists`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        val completedPlan = PlanningSessionEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@PlanBotCommandTest.userId
            status = PlanningSessionStatus.COMPLETED
            summary = "Week 20 plan summary"
        }
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(completedPlan)

        command.handle(context())

        val captor = argumentCaptor<ChannelMessage>()
        verify(channel).send(captor.capture())
        val msg = captor.firstValue as ChannelMessage.Choice
        assertEquals("Current plan: Week 20 plan summary", msg.prompt)
        assertEquals(listOf(
            ChoiceOption(PlanConfirmationRegistry.OPTION_KEEP, "Keep"),
            ChoiceOption(PlanConfirmationRegistry.OPTION_NEW, "Plan again"),
        ), msg.options)

        val pending = planConfirmationRegistry.get(chatId)
        assertNotNull(pending)
        assertEquals(userId, pending.userId)
        assertNull(pending.existingSessionId)

        verify(orchestrator, never()).start(any(), any())
    }

    @Test
    fun `starts new session normally when no existing plan or active session`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        val newSessionId = UUID.randomUUID()
        whenever(orchestrator.start(eq(userId), eq(channel))).thenReturn(newSessionId)

        command.handle(context())

        verify(orchestrator).start(userId, channel)
        verify(sessionRegistry).put(chatId, newSessionId)
        verify(channel, never()).send(any())
        assertNull(planConfirmationRegistry.get(chatId))
    }

    @Test
    fun `starts new session when completed plan exists but has no summary`() {
        whenever(sessionRegistry.get(chatId)).thenReturn(null)
        val completedPlan = PlanningSessionEntity().apply {
            id = UUID.randomUUID()
            this.userId = this@PlanBotCommandTest.userId
            status = PlanningSessionStatus.COMPLETED
            summary = null
        }
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(completedPlan)
        val newSessionId = UUID.randomUUID()
        whenever(orchestrator.start(eq(userId), eq(channel))).thenReturn(newSessionId)

        command.handle(context())

        verify(orchestrator).start(userId, channel)
        verify(sessionRegistry).put(chatId, newSessionId)
    }

    @Test
    fun `starts new session when active session is stale (no orchestrator phase)`() {
        val staleSessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(staleSessionId)
        whenever(orchestrator.phase(staleSessionId)).thenReturn(null)
        whenever(planningSessionService.findCurrentPlan(userId)).thenReturn(null)
        val newSessionId = UUID.randomUUID()
        whenever(orchestrator.start(eq(userId), eq(channel))).thenReturn(newSessionId)

        command.handle(context())

        verify(orchestrator).start(userId, channel)
        verify(sessionRegistry).put(chatId, newSessionId)
    }
}
