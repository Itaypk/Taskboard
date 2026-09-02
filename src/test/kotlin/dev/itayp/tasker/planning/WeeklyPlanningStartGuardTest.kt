package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
import java.util.UUID
import kotlin.test.assertTrue

/**
 * The empty-backlog guard on [WeeklyPlanningOrchestrator.start]. It lives in the orchestrator, not
 * a controller, so all three entry points are covered — the web drawer, the Telegram weekly cron
 * and dev planning — and neither a stale client nor the cron can spend an AI call planning a slate
 * the prompt would render as "(none)".
 */
class WeeklyPlanningStartGuardTest {

    private val planningSessionService: PlanningSessionService = mock()
    private val plannerTaskSelector: PlannerTaskSelector = mock()
    private val userSettingsService: UserSettingsService = mock()

    // Fixed Wednesday 2026-05-13T08:00Z; the user is on UTC, so "today" is that same date.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-13T08:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.parse("2026-05-13")
    private val weekStart: LocalDate = LocalDate.parse("2026-05-18")
    private val userId = UUID.randomUUID()

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
        messageSource = StaticMessageSource(),
        userSettingsService = userSettingsService,
        plannerTaskSelector = plannerTaskSelector,
        clock = clock,
        model = "test-model",
    )

    @Test
    fun `start refuses when nothing in the backlog is plannable`() {
        stubSettings()
        whenever(plannerTaskSelector.countCandidates(userId, today)).thenReturn(0)
        val channel = BufferedConversationChannel()

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
}
