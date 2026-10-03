package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelCapabilities
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.channel.PlainTextMessageFormatter
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import dev.itayp.tasker.channel.ChannelType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two ways [WeeklyPlanningOrchestrator.start] can fail before a conversation exists, and why
 * neither may leave an ACTIVE session row behind.
 *
 * The empty-backlog guard lives in the orchestrator, not a controller, so all three entry points
 * are covered — the web drawer, the Telegram weekly cron and dev planning — and neither a stale
 * client nor the cron can spend an AI call planning a slate the prompt would render as "(none)".
 *
 * A failing kickoff send is the other case: the cron pushes to Telegram, which answers "chat not
 * found" for a user who linked their account but never opened the bot chat. The session must be
 * abandoned rather than stranded, because `WebPlanningController.entry` only looks for a finalized
 * plan when no session is active — so an orphan row hides the user's plan for *other* weeks.
 */
class WeeklyPlanningStartGuardTest {

    private val planningSessionService: PlanningSessionService = mock()
    private val plannerTaskSelector: PlannerTaskSelector = mock()
    private val userSettingsService: UserSettingsService = mock()

    /** Keyed to `en-US` exactly: [StaticMessageSource] resolves a code per locale, with no
     *  parent-locale fallback, and that is the tag [stubSettings] stores. */
    private val messages = StaticMessageSource().apply {
        val locale = Locale.forLanguageTag("en-US")
        addMessage("planning.capacity.question", locale, "How much can you take on?")
        addMessage("planning.capacity.option.light", locale, "Light")
        addMessage("planning.capacity.option.normal", locale, "Normal")
        addMessage("planning.capacity.option.heavy", locale, "Heavy")
        addMessage("planning.capacity.option.skip", locale, "Skip")
    }

    // Fixed Wednesday 2026-05-13T08:00Z; the user is on UTC, so "today" is that same date.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-13T08:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.parse("2026-05-13")
    private val weekStart: LocalDate = LocalDate.parse("2026-05-18")
    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val session = PlanningSession(
        id = sessionId,
        userId = userId,
        conversationId = null,
        status = PlanningSessionStatus.ACTIVE,
        startedAt = clock.instant(),
        weekStart = weekStart,
        endedAt = null,
        summary = null,
    )

    private val orchestrator = WeeklyPlanningOrchestrator(
        planningSessionService = planningSessionService,
        planFinalizationService = mock(),
        promptAssembler = mock(),
        aiConversationManager = mock<AiConversationManager>(),
        planSubmissionInbox = mock(),
        planningToolContext = mock(),
        plannedTaskService = mock(),
        reconciliationService = mock(),
        backlogTaskService = mock<BacklogTaskService>(),
        toolRegistry = mock<ToolRegistry>(),
        objectMapper = ObjectMapper(),
        messageSource = messages,
        userSettingsService = userSettingsService,
        plannerTaskSelector = plannerTaskSelector,
        clock = clock,
        model = "test-model",
    )

    @Test
    fun `start refuses when nothing in the backlog is plannable`() {
        stubSettings()
        whenever(plannerTaskSelector.countCandidates(userId, today)).thenReturn(0)
        val channel = BufferedConversationChannel(ChannelType.DEV)

        assertThrows<NoPlannableTasksException> { orchestrator.start(userId, channel, weekStart) }

        // No session row and no capacity question: a session left ACTIVE would block the next
        // attempt, and the question is unanswerable when there is nothing to schedule.
        // A matcher per argument: startSession's conversationId has a default, so this call
        // site compiles into startSession(id, week, null).
        verify(planningSessionService, never()).startSession(any(), any(), anyOrNull())
        assertTrue(channel.drain().isEmpty(), "nothing should have been sent to the channel")
    }

    private fun stubSettings() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(
            UserSettings(
                userId = userId,
                displayName = null,
                contextBlock = null,
                timeZone = "UTC",
                preferredLanguage = "en-US",
                calendarInviteEmail = false,
                gender = null,
                agentDescription = null,
                planningCron = null,
                weekStartDay = "MONDAY",
                autoArchiveDays = null,
            ),
        )
    }

    @Test
    fun `start abandons the session when the kickoff message cannot be delivered`() {
        stubSettings()
        whenever(plannerTaskSelector.countCandidates(userId, today)).thenReturn(3)
        whenever(planningSessionService.startSession(any(), any(), anyOrNull())).thenReturn(session)
        val failure = IllegalStateException("chat not found")

        val thrown = assertThrows<IllegalStateException> {
            orchestrator.start(userId, UndeliverableChannel(failure), weekStart)
        }

        assertSame(failure, thrown, "the delivery failure should reach the caller unchanged")
        verify(planningSessionService).abandonSession(userId, sessionId)
        // The in-memory state goes with it, or `entry` would offer the dead session as resumable.
        assertNull(orchestrator.phase(sessionId))
    }

    @Test
    fun `a failure while abandoning does not mask the delivery failure`() {
        stubSettings()
        whenever(plannerTaskSelector.countCandidates(userId, today)).thenReturn(3)
        whenever(planningSessionService.startSession(any(), any(), anyOrNull())).thenReturn(session)
        whenever(planningSessionService.abandonSession(eq(userId), eq(sessionId)))
            .thenThrow(NoSuchElementException("gone"))
        val failure = IllegalStateException("chat not found")

        val thrown = assertThrows<IllegalStateException> {
            orchestrator.start(userId, UndeliverableChannel(failure), weekStart)
        }

        assertSame(failure, thrown)
    }

    /** A channel whose first [send] fails the way Telegram does for a never-opened chat. */
    private class UndeliverableChannel(private val failure: RuntimeException) : ConversationChannel {
        override val type = ChannelType.TELEGRAM
        override val capabilities = ChannelCapabilities(
            supportsAutocompletions = true,
            supportsInlineButtons = true,
        )
        override val formatter: MessageFormatter = PlainTextMessageFormatter
        override fun send(message: ChannelMessage): Unit = throw failure
    }
}
