package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.tasker.ai.TurnOutcome
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertTrue

/**
 * The revise-mode half of `docs/FREE-TEXT-CAPTURE.md` D3a: a planning conversation reached from a
 * free-text message opens with what the user already said, instead of asking them to repeat it.
 */
class WeeklyPlanningRevisionSeedTest {

    private val planningSessionService: PlanningSessionService = mock()
    private val aiConversationManager: AiConversationManager = mock()
    private val promptAssembler: WeeklyPlanningPromptAssembler = mock()
    private val plannedTaskService: PlannedTaskService = mock()

    private val orchestrator = WeeklyPlanningOrchestrator(
        planningSessionService = planningSessionService,
        planFinalizationService = mock(),
        promptAssembler = promptAssembler,
        aiConversationManager = aiConversationManager,
        planSubmissionInbox = mock(),
        planningToolContext = mock(),
        plannedTaskService = plannedTaskService,
        reconciliationService = mock(),
        backlogTaskService = mock<BacklogTaskService>(),
        toolRegistry = mock<ToolRegistry>(),
        objectMapper = ObjectMapper(),
        messageSource = StaticMessageSource(),
        userSettingsService = mock<UserSettingsService>(),
        plannerTaskSelector = mock<PlannerTaskSelector>(),
        clock = Clock.systemUTC(),
        model = "test-model",
    )

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val conversationId = UUID.randomUUID()

    @BeforeEach
    fun stub() {
        whenever(planningSessionService.findById(userId, sessionId)).thenReturn(
            PlanningSession(
                id = sessionId,
                userId = userId,
                conversationId = null,
                status = PlanningSessionStatus.COMPLETED,
                startedAt = Instant.parse("2026-06-08T08:00:00Z"),
                weekStart = LocalDate.parse("2026-06-08"),
                endedAt = Instant.parse("2026-06-08T08:30:00Z"),
                summary = "a plan",
            ),
        )
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(emptyList())
        whenever(promptAssembler.assembleRevisionSystemPrompt(any(), any(), any(), any())).thenReturn("system")
        whenever(promptAssembler.renderReviseKickoff()).thenReturn("I'd like to revise my current plan.")
        whenever(aiConversationManager.startConversation(any(), any())).thenReturn(conversationId)
        // Blank text: nothing is rendered, so the test sees only the kickoff we care about.
        whenever(aiConversationManager.sendMessage(any(), any())).thenReturn(TurnOutcome.TextReply(""))
    }

    @Test
    fun `an instruction is appended to the revise kickoff`() {
        orchestrator.startRevision(userId, sessionId, BufferedConversationChannel(), "move my gym session to Thursday")

        verify(aiConversationManager).sendMessage(eq(conversationId), org.mockito.kotlin.check {
            assertTrue(it.startsWith("I'd like to revise my current plan."))
            assertTrue(it.contains("move my gym session to Thursday"))
        })
    }

    @Test
    fun `a typed revise sends the kickoff unchanged`() {
        orchestrator.startRevision(userId, sessionId, BufferedConversationChannel())

        verify(aiConversationManager).sendMessage(eq(conversationId), eq("I'd like to revise my current plan."))
    }

    @Test
    fun `a blank instruction is ignored`() {
        orchestrator.startRevision(userId, sessionId, BufferedConversationChannel(), "   ")

        verify(aiConversationManager).sendMessage(eq(conversationId), eq("I'd like to revise my current plan."))
    }
}
