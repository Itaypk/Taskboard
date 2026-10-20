package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.AiConversationManager
import dev.itayp.tasker.ai.RequestedToolCall
import dev.itayp.tasker.ai.TurnOutcome
import dev.itayp.nescioquid.openrouter.tool.ToolRegistry
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import dev.itayp.tasker.channel.ChannelType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
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
import kotlin.test.assertTrue

/**
 * The model occasionally sends `ask_choice` arguments that don't match the schema (options as arrays
 * rather than `{id, label}` objects). That used to throw out of the request, leaving the call without
 * a tool result; now the model gets an error result and one chance to correct itself.
 */
class WeeklyPlanningMalformedToolCallTest {

    private val planningSessionService: PlanningSessionService = mock()
    private val aiConversationManager: AiConversationManager = mock()
    private val promptAssembler: WeeklyPlanningPromptAssembler = mock()
    private val plannedTaskService: PlannedTaskService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messageSource = StaticMessageSource()
    private val toolRegistry: ToolRegistry = mock()

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
        toolRegistry = toolRegistry,
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
        // Revise is the shortest entry point into processOutcome (see WeeklyPlanningEmptyTurnTest).
        whenever(aiConversationManager.sendMessage(any(), any())).thenReturn(malformedCall("call-1"))
        whenever(toolRegistry.get("ask_choice")).thenReturn(AskChoiceTool())
    }

    @Test
    fun `a malformed ask_choice is answered with an error and the corrected call is rendered`() {
        whenever(aiConversationManager.continueConversation(conversationId)).thenReturn(
            TurnOutcome.ToolCalls(
                listOf(
                    RequestedToolCall(
                        "call-2", "ask_choice",
                        """{"prompt":"Keep it?","options":[{"id":"yes","label":"Yes"},{"id":"discuss","label":"Talk"}]}""",
                    ),
                ),
            ),
        )
        val channel = BufferedConversationChannel(ChannelType.DEV)

        orchestrator.startRevision(userId, sessionId, channel)

        // The bad call still gets its tool result, so the transcript stays valid for the provider.
        verify(aiConversationManager).recordToolResult(
            eq(conversationId), eq("call-1"), eq("ask_choice"), argThat { contains("invalid arguments") },
        )
        verify(aiConversationManager, times(1)).continueConversation(conversationId)
        val choice = channel.drain().filterIsInstance<ChannelMessage.Choice>().single()
        assertEquals(listOf("yes", "discuss"), choice.options.map { it.id })
        assertEquals(WeeklyPlanningOrchestrator.Phase.AWAITING_INTERACTIVE_REPLY, orchestrator.phase(sessionId))
    }

    @Test
    fun `a malformed ask_choice repeated after the correction still says something`() {
        whenever(aiConversationManager.continueConversation(conversationId)).thenReturn(malformedCall("call-2"))
        val channel = BufferedConversationChannel(ChannelType.DEV)

        orchestrator.startRevision(userId, sessionId, channel)

        verify(aiConversationManager, times(1)).continueConversation(conversationId)
        verify(aiConversationManager).recordToolResult(eq(conversationId), eq("call-2"), eq("ask_choice"), any())
        val drained = channel.drain()
        assertTrue(drained.none { it is ChannelMessage.Choice })
        assertEquals(listOf("Sorry, try again."), drained.filterIsInstance<ChannelMessage.Text>().map { it.text })
        assertEquals(WeeklyPlanningOrchestrator.Phase.CONVERSING, orchestrator.phase(sessionId))
    }

    // The shape from issue #307: options as arrays instead of {id, label} objects.
    private fun malformedCall(id: String) = TurnOutcome.ToolCalls(
        listOf(
            RequestedToolCall(id, "ask_choice", """{"prompt":"Keep it?","options":[["yes","Yes"],["discuss","Talk"]]}"""),
        ),
    )
}
