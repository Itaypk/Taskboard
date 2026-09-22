package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.tasker.ai.TurnOutcome
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals

/**
 * A model turn that comes back with neither content nor tool calls ([TurnOutcome.Empty]) used to
 * end the turn having rendered nothing at all — on the web that is a drawer that simply stops
 * responding, with no way for the user to tell a broken session from a slow one.
 */
class WeeklyPlanningEmptyTurnTest {

    private val planningSessionService: PlanningSessionService = mock()
    private val aiConversationManager: AiConversationManager = mock()
    private val promptAssembler: WeeklyPlanningPromptAssembler = mock()
    private val plannedTaskService: PlannedTaskService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messageSource = StaticMessageSource()

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
        messageSource = messageSource,
        userSettingsService = userSettingsService,
        plannerTaskSelector = mock<PlannerTaskSelector>(),
        clock = Clock.systemUTC(),
        model = "test-model",
    )

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val conversationId = UUID.randomUUID()

    @BeforeEach
    fun stub() {
        messageSource.addMessage("planning.empty_turn", Locale.ENGLISH, "Sorry, try again.")
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
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
        whenever(promptAssembler.renderReviseKickoff()).thenReturn("kickoff")
        whenever(aiConversationManager.startConversation(any(), any())).thenReturn(conversationId)
        // Every entry point funnels through the same processOutcome; revise is the shortest one
        // that reaches it without first walking the capacity and reconciliation questions.
        whenever(aiConversationManager.sendMessage(any(), any())).thenReturn(TurnOutcome.Empty)
    }

    @Test
    fun `an empty turn is retried once and the retry's reply is rendered`() {
        whenever(aiConversationManager.continueConversation(conversationId))
            .thenReturn(TurnOutcome.TextReply("here we go"))
        val channel = BufferedConversationChannel()

        orchestrator.startRevision(userId, sessionId, channel)

        verify(aiConversationManager, times(1)).continueConversation(conversationId)
        val texts = channel.drain().filterIsInstance<ChannelMessage.Text>().map { it.text }
        assertEquals(listOf("here we go"), texts)
    }

    @Test
    fun `an empty turn that survives the retry still says something`() {
        whenever(aiConversationManager.continueConversation(conversationId)).thenReturn(TurnOutcome.Empty)
        val channel = BufferedConversationChannel()

        orchestrator.startRevision(userId, sessionId, channel)

        // Retried exactly once — a model that answers nothing twice is not going to on the third try,
        // and every attempt is a paid call the user is waiting on.
        verify(aiConversationManager, times(1)).continueConversation(conversationId)
        val texts = channel.drain().filterIsInstance<ChannelMessage.Text>().map { it.text }
        assertEquals(listOf("Sorry, try again."), texts)
        // The session stays open, so anything the user sends next resumes it.
        assertEquals(WeeklyPlanningOrchestrator.Phase.CONVERSING, orchestrator.phase(sessionId))
    }
}
