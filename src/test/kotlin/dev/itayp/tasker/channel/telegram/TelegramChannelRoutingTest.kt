package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.capture.CaptureEntry
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.QuickAddState
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.telegram.commands.BotCommandContext
import dev.itayp.tasker.channel.telegram.commands.BotCommandDispatcher
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.notification.ReminderActionHandler
import dev.itayp.tasker.planning.CaptureIntent
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User as TelegramUser
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers where an inbound message is routed — the ordering in `handleUpdate` that
 * `docs/FREE-TEXT-CAPTURE.md` depends on, not the capture itself (see `QuickAddFlowTest`).
 */
class TelegramChannelRoutingTest {

    private val userRepository: UserRepository = mock()
    private val orchestrator: WeeklyPlanningOrchestrator = mock()
    private val sessionRegistry: TelegramSessionRegistry = mock()
    private val commandDispatcher: BotCommandDispatcher = mock()
    private val planConfirmationRegistry: PlanConfirmationRegistry = mock()
    private val quickAddRegistry: QuickAddRegistry = mock()
    private val quickAddFlow: QuickAddFlow = mock()
    private val mediaExtractor: TelegramMediaExtractor = mock()
    private val reminderActionHandler: ReminderActionHandler = mock()
    private val aiAccessService: AiAccessService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val telegramClient: TelegramClient = mock()

    private val messageSource = StaticMessageSource().apply {
        addMessage("command.unprompted.unclear", Locale.ENGLISH, "Send me a task")
        addMessage("quickadd.in_session", Locale.ENGLISH, "We're planning right now")
    }

    private val channel = TelegramChannel(
        botUsername = "bot",
        botToken = "token",
        userRepository = userRepository,
        orchestrator = orchestrator,
        sessionRegistry = sessionRegistry,
        commandDispatcher = commandDispatcher,
        commandHandlers = emptyList(),
        planConfirmationRegistry = planConfirmationRegistry,
        quickAddRegistry = quickAddRegistry,
        quickAddFlow = quickAddFlow,
        mediaExtractor = mediaExtractor,
        reminderActionHandler = reminderActionHandler,
        aiAccessService = aiAccessService,
        userSettingsService = userSettingsService,
        messageSource = messageSource,
        telegramClient = telegramClient,
        clock = Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC),
    )

    private val userId = UUID.randomUUID()
    private val chatId = 42L
    private val telegramUserId = 7L

    @BeforeEach
    fun stub() {
        whenever(userRepository.findByTelegramId(telegramUserId))
            .thenReturn(UserEntity().apply { id = userId; telegramId = telegramUserId })
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)
    }

    private fun textUpdate(text: String): Update {
        val from: TelegramUser = mock()
        whenever(from.id).thenReturn(telegramUserId)
        val message: Message = mock()
        whenever(message.hasText()).thenReturn(true)
        whenever(message.text).thenReturn(text)
        whenever(message.chatId).thenReturn(chatId)
        whenever(message.from).thenReturn(from)
        val update: Update = mock()
        whenever(update.hasMessage()).thenReturn(true)
        whenever(update.message).thenReturn(message)
        return update
    }

    private fun callbackUpdate(data: String): Update {
        val from: TelegramUser = mock()
        whenever(from.id).thenReturn(telegramUserId)
        val message: Message = mock()
        whenever(message.chatId).thenReturn(chatId)
        val callback: CallbackQuery = mock()
        whenever(callback.id).thenReturn("cb-1")
        whenever(callback.data).thenReturn(data)
        whenever(callback.from).thenReturn(from)
        whenever(callback.message).thenReturn(message)
        val update: Update = mock()
        whenever(update.hasCallbackQuery()).thenReturn(true)
        whenever(update.callbackQuery).thenReturn(callback)
        return update
    }

    @Test
    fun `a message with no command and nothing in progress opens an unprompted capture`() {
        whenever(quickAddFlow.beginUnprompted(eq(userId), any(), eq("call the plumber")))
            .thenReturn(CaptureEntry.Captured(null))

        channel.consume(textUpdate("call the plumber"))

        verify(quickAddFlow).beginUnprompted(eq(userId), any(), eq("call the plumber"))
        verify(orchestrator, never()).handleInbound(any(), any(), any())
    }

    @Test
    fun `a message during a live planning session stays with the orchestrator`() {
        // People write in bursts and correct themselves a message later, so a stray message mid-
        // session is part of that conversation — never a capture. This ordering is load-bearing.
        val sessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(sessionId)
        whenever(orchestrator.phase(sessionId)).thenReturn(WeeklyPlanningOrchestrator.Phase.CONVERSING)

        channel.consume(textUpdate("actually, make that Thursday"))

        verify(orchestrator).handleInbound(eq(sessionId), eq(ChannelInbound.Text("actually, make that Thursday")), any())
        verify(quickAddFlow, never()).beginUnprompted(any(), any(), any())
    }

    @Test
    fun `a stale session registry entry does not block a capture`() {
        val staleSessionId = UUID.randomUUID()
        whenever(sessionRegistry.get(chatId)).thenReturn(staleSessionId)
        whenever(orchestrator.phase(staleSessionId)).thenReturn(null)
        whenever(quickAddFlow.beginUnprompted(any(), any(), any())).thenReturn(CaptureEntry.Captured(null))

        channel.consume(textUpdate("buy milk"))

        verify(sessionRegistry).remove(chatId)
        verify(quickAddFlow).beginUnprompted(eq(userId), any(), eq("buy milk"))
    }

    @Test
    fun `an in-progress capture takes the message before anything else does`() {
        val state = QuickAddState.AwaitingDescription(Instant.parse("2026-06-11T09:59:00Z"))
        whenever(quickAddRegistry.get(chatId)).thenReturn(state)
        whenever(quickAddFlow.handleInbound(any(), any(), any(), any())).thenReturn(CaptureEntry.Captured(null))

        channel.consume(textUpdate("make it two"))

        verify(quickAddFlow).handleInbound(eq(userId), any(), eq(state), eq(ChannelInbound.Text("make it two")))
        verify(quickAddFlow, never()).beginUnprompted(any(), any(), any())
    }

    @Test
    fun `a routed read-only intent runs its command directly`() {
        whenever(quickAddFlow.beginUnprompted(any(), any(), any()))
            .thenReturn(CaptureEntry.Routed(CaptureIntent.CURRENT, "what's on for today?"))
        whenever(commandDispatcher.dispatch(any(), any())).thenReturn(true)

        channel.consume(textUpdate("what's on for today?"))

        val context = argumentCaptor<BotCommandContext>()
        verify(commandDispatcher).dispatch(eq("/current"), context.capture())
        assertEquals(userId, context.firstValue.userId)
        assertTrue(context.firstValue.inferred)
        assertEquals("what's on for today?", context.firstValue.inferredFrom)
    }

    @Test
    fun `a routed plan intent goes through the plan command marked as inferred`() {
        whenever(quickAddFlow.beginUnprompted(any(), any(), any()))
            .thenReturn(CaptureEntry.Routed(CaptureIntent.PLAN, "let's sort out my week"))
        whenever(commandDispatcher.dispatch(any(), any())).thenReturn(true)

        channel.consume(textUpdate("let's sort out my week"))

        val context = argumentCaptor<BotCommandContext>()
        verify(commandDispatcher).dispatch(eq("/plan"), context.capture())
        // `inferred` is what makes /plan offer a way out of a conversation nobody asked for.
        assertTrue(context.firstValue.inferred)
    }

    @Test
    fun `an unclear intent answers with what the bot can do and runs no command`() {
        whenever(quickAddFlow.beginUnprompted(any(), any(), any()))
            .thenReturn(CaptureEntry.Routed(CaptureIntent.UNCLEAR, "hi"))

        channel.consume(textUpdate("hi!"))

        verify(commandDispatcher, never()).dispatch(any(), any())
    }

    @Test
    fun `a user who has opted out of AI is told what the bot can do instead of being charged for a capture`() {
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)

        channel.consume(textUpdate("call the plumber"))

        verify(quickAddFlow, never()).beginUnprompted(any(), any(), any())
    }


    @Test
    fun `choosing to revise hands the triggering message to the revision conversation`() {
        val sessionId = UUID.randomUUID()
        whenever(planConfirmationRegistry.get(chatId)).thenReturn(
            PlanConfirmationRegistry.PendingConfirmation(
                userId = userId,
                existingSessionId = null,
                replanWeekStart = null,
                revisableSessionId = sessionId,
                revisionSeed = "move my gym session to Thursday",
            ),
        )

        channel.consume(callbackUpdate(PlanConfirmationRegistry.OPTION_REVISE))

        verify(orchestrator).startRevision(eq(userId), eq(sessionId), any(), eq("move my gym session to Thursday"))
    }
}
