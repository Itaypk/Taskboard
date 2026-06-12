package dev.itayp.tasker.capture

import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.planning.ClarifyOption
import dev.itayp.tasker.planning.ClarificationExchange
import dev.itayp.tasker.planning.SuggestionOutcome
import dev.itayp.tasker.planning.TaskDraft
import dev.itayp.tasker.planning.TaskSuggestionAgent
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickAddFlowTest {

    private val suggestionAgent: TaskSuggestionAgent = mock()
    private val backlogTaskService: BacklogTaskService = mock()
    private val boardMembershipService: BoardMembershipService = mock()
    private val categoryService: BacklogTaskCategoryService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val meterRegistry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC)

    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
    }

    private val flow = QuickAddFlow(
        suggestionAgent, backlogTaskService, boardMembershipService, categoryService,
        userSettingsService, messageSource, meterRegistry, clock,
    )

    private val userId = UUID.randomUUID()
    private val boardId = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()

    @BeforeEach
    fun stub() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(categoryService.getAllForUser(userId)).thenReturn(
            listOf(BacklogTaskCategory(categoryId, boardId, "Groceries", CategoryColor.MINT)),
        )
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
    }

    private fun channel() = BufferedConversationChannel()

    private fun draft(title: String = "Buy milk") =
        TaskDraft(title = title, categoryId = categoryId.toString(), priority = "medium")

    @Test
    fun `bare add asks for a description`() {
        val channel = channel()
        val state = flow.begin(userId, channel, null)

        assertIs<QuickAddState.AwaitingDescription>(state)
        assertIs<ChannelMessage.Text>(channel.drain().single())
    }

    @Test
    fun `add with text drafts a task and shows a confirmation card`() {
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("buy milk"), any(), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(draft()))
        val channel = channel()

        val state = flow.begin(userId, channel, "buy milk")

        assertIs<QuickAddState.AwaitingConfirmation>(state)
        val card = channel.drain().single()
        assertIs<ChannelMessage.Choice>(card)
        assertTrue(card.prompt.contains("Buy milk"))
        assertEquals(
            listOf(QuickAddFlow.OPTION_SAVE, QuickAddFlow.OPTION_ADJUST, QuickAddFlow.OPTION_CANCEL),
            card.options.map { it.id },
        )
    }

    @Test
    fun `save persists the draft and ends the flow`() {
        val created: BacklogTask = mock()
        whenever(created.id).thenReturn(UUID.randomUUID())
        whenever(created.title).thenReturn("Buy milk")
        whenever(backlogTaskService.createTask(eq(userId), eq(boardId), any())).thenReturn(created)

        val state = QuickAddState.AwaitingConfirmation(draft(), "buy milk", emptyList(), clock.instant())
        val channel = channel()

        val next = flow.handleInbound(userId, channel, state, ChannelInbound.Selection(QuickAddFlow.OPTION_SAVE))

        assertNull(next)
        verify(backlogTaskService).createTask(eq(userId), eq(boardId), check {
            assertEquals("Buy milk", it.title)
            assertEquals(categoryId.toString(), it.categoryId)
        })
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.outcome", "result", "saved").count())
    }

    @Test
    fun `cancel ends the flow without saving`() {
        val state = QuickAddState.AwaitingConfirmation(draft(), "buy milk", emptyList(), clock.instant())
        val channel = channel()

        val next = flow.handleInbound(userId, channel, state, ChannelInbound.Selection(QuickAddFlow.OPTION_CANCEL))

        assertNull(next)
        verify(backlogTaskService, never()).createTask(any(), any(), any())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.outcome", "result", "cancelled").count())
    }

    @Test
    fun `free text on the card is treated as an adjustment`() {
        whenever(suggestionAgent.quickAddRevise(eq(userId), eq("buy milk"), any(), eq("make it oat milk"), any(), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(draft("Buy oat milk")))
        val state = QuickAddState.AwaitingConfirmation(draft(), "buy milk", emptyList(), clock.instant())
        val channel = channel()

        val next = flow.handleInbound(userId, channel, state, ChannelInbound.Text("make it oat milk"))

        val confirmation = assertIs<QuickAddState.AwaitingConfirmation>(next)
        assertEquals("Buy oat milk", confirmation.draft.title)
    }

    @Test
    fun `vague input asks a clarifying question, then drafts from the answer`() {
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("fix it"), any(), eq(false)))
            .thenReturn(SuggestionOutcome.Clarify("Which area?", listOf(ClarifyOption("home", "Home"), ClarifyOption("work", "Work"))))
            .thenReturn(SuggestionOutcome.Draft(draft("Fix the kitchen sink")))
        val channel = channel()

        val clarifying = flow.begin(userId, channel, "fix it")

        val state = assertIs<QuickAddState.AwaitingClarification>(clarifying)
        assertEquals(1, state.rounds)
        val q = assertIs<ChannelMessage.Choice>(channel.drain().single())
        // Options are normalised to short ids and gain a "let me explain" escape.
        assertEquals(listOf("o0", "o1", QuickAddFlow.OPTION_EXPLAIN), q.options.map { it.id })

        // Answering the first (id "o0" → "Home") folds the Q&A into the next drafting call.
        val answered = flow.handleInbound(userId, channel(), state, ChannelInbound.Selection("o0"))

        assertIs<QuickAddState.AwaitingConfirmation>(answered)
        val clarificationsCaptor = argumentCaptor<List<ClarificationExchange>>()
        verify(suggestionAgent, times(2)).quickAddDraft(eq(userId), eq("fix it"), clarificationsCaptor.capture(), eq(false))
        assertEquals(ClarificationExchange("Which area?", "Home"), clarificationsCaptor.lastValue.single())
    }

    @Test
    fun `clarification budget forces a draft on the last round`() {
        val state = QuickAddState.AwaitingClarification(
            op = PendingOp.Draft("fix it", emptyList()),
            question = "Which area?",
            options = listOf(ClarifyOption("o0", "Home")),
            rounds = QuickAddFlow.MAX_CLARIFY_ROUNDS,
            createdAt = clock.instant(),
        )
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("fix it"), any(), eq(true)))
            .thenReturn(SuggestionOutcome.Draft(draft("Fix the sink")))

        val next = flow.handleInbound(userId, channel(), state, ChannelInbound.Text("the kitchen"))

        assertIs<QuickAddState.AwaitingConfirmation>(next)
        // Must have called the agent in must-draft mode (mustDraft = true).
        verify(suggestionAgent).quickAddDraft(eq(userId), eq("fix it"), any(), eq(true))
    }

    @Test
    fun `unparseable output ends the flow`() {
        whenever(suggestionAgent.quickAddDraft(any(), any(), any(), any()))
            .thenReturn(SuggestionOutcome.Unparseable)
        val channel = channel()

        val next = flow.begin(userId, channel, "asdfgh")

        assertNull(next)
        assertIs<ChannelMessage.Text>(channel.drain().single())
    }
}
