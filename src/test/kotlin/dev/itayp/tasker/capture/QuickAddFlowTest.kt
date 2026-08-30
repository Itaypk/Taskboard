package dev.itayp.tasker.capture

import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.InboundAttachment
import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.oneoff.CreatedEvents
import dev.itayp.tasker.oneoff.OneOffEvent
import dev.itayp.tasker.oneoff.OneOffEventDraft
import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.planning.CaptureIntent
import dev.itayp.tasker.planning.CapturedItem
import dev.itayp.tasker.planning.ClarifyOption
import dev.itayp.tasker.planning.ClarificationExchange
import dev.itayp.tasker.planning.EventDraft
import dev.itayp.tasker.planning.SuggestionOutcome
import dev.itayp.tasker.planning.TaskDraft
import dev.itayp.tasker.planning.TaskSuggestionAgent
import dev.itayp.tasker.planning.UnsupportedModalityException
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
    private val oneOffEventService: OneOffEventService = mock()
    private val boardMembershipService: BoardMembershipService = mock()
    private val categoryService: BacklogTaskCategoryService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val meterRegistry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC)

    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
    }

    /** Allows everything by default; the rate-limit test swaps in a limiter that refuses. */
    private var rateLimiter = RateLimiter { true }

    private val flow = QuickAddFlow(
        suggestionAgent, backlogTaskService, oneOffEventService, boardMembershipService, categoryService,
        userSettingsService, messageSource, meterRegistry, { key -> rateLimiter.tryConsume(key) }, clock,
    )

    private val userId = UUID.randomUUID()
    private val boardId = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()

    @BeforeEach
    fun stub() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(userSettings())
        whenever(categoryService.getAllForUser(userId)).thenReturn(
            listOf(BacklogTaskCategory(categoryId, boardId, "Groceries", CategoryColor.MINT)),
        )
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
    }

    private fun userSettings() = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = "UTC",
        preferredLanguage = "en",
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )

    private fun channel() = BufferedConversationChannel()

    private fun taskDraft(title: String = "Buy milk") =
        TaskDraft(title = title, categoryId = categoryId.toString(), priority = "medium")

    private fun taskItem(title: String = "Buy milk"): CapturedItem.Task = CapturedItem.Task(taskDraft(title))

    private fun eventItem(
        title: String = "Parent-teacher conference",
        start: String = "2026-07-15T19:30:00Z",
        end: String? = "2026-07-15T20:30:00Z",
        location: String? = "School auditorium",
    ): CapturedItem.Event = CapturedItem.Event(
        EventDraft(title = title, startIso = start, endIso = end, location = location)
    )

    @Test
    fun `bare add asks for a description`() {
        val channel = channel()
        val state = flow.begin(userId, channel, null)

        assertIs<QuickAddState.AwaitingDescription>(state)
        assertIs<ChannelMessage.Text>(channel.drain().single())
    }

    @Test
    fun `add with text drafts a task and shows a confirmation card`() {
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("buy milk"), any(), eq(false), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem())))
        val channel = channel()

        val state = flow.begin(userId, channel, "buy milk")

        val confirmation = assertIs<QuickAddState.AwaitingConfirmation>(state)
        assertEquals(1, confirmation.items.size)
        val card = channel.drain().single()
        assertIs<ChannelMessage.Choice>(card)
        assertTrue(card.prompt.contains("Buy milk"))
        assertEquals(
            listOf(QuickAddFlow.OPTION_SAVE, QuickAddFlow.OPTION_ADJUST, QuickAddFlow.OPTION_CANCEL),
            card.options.map { it.id },
        )
    }

    @Test
    fun `add captures a mixed batch of one task and one event`() {
        whenever(suggestionAgent.quickAddDraft(eq(userId), any(), any(), eq(false), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(listOf(eventItem(), taskItem("Prep questions"))))
        val channel = channel()

        val state = flow.begin(userId, channel, "parent-teacher conference Wed 7:30pm + prep questions")

        val confirmation = assertIs<QuickAddState.AwaitingConfirmation>(state)
        assertEquals(2, confirmation.items.size)
        val card = assertIs<ChannelMessage.Choice>(channel.drain().single())
        assertTrue(card.prompt.contains("Parent-teacher conference"))
        assertTrue(card.prompt.contains("Prep questions"))
    }

    @Test
    fun `save persists tasks and events and ends the flow`() {
        val createdTask: BacklogTask = mock()
        whenever(createdTask.id).thenReturn(UUID.randomUUID())
        whenever(createdTask.title).thenReturn("Prep questions")
        whenever(backlogTaskService.createTask(eq(userId), eq(boardId), any())).thenReturn(createdTask)
        val createdEvent = OneOffEvent(
            id = UUID.randomUUID(),
            userId = userId,
            boardId = boardId,
            title = "Parent-teacher conference",
            startsAt = Instant.parse("2026-07-15T19:30:00Z"),
            endsAt = Instant.parse("2026-07-15T20:30:00Z"),
            location = "School auditorium",
            notes = null,
            icalUid = "uid-1",
            cancelledAt = null,
        )
        whenever(oneOffEventService.createEvents(eq(userId), eq(boardId), any()))
            .thenReturn(CreatedEvents(listOf(createdEvent), invitesScheduled = true))

        val state = QuickAddState.AwaitingConfirmation(
            items = listOf(eventItem(), taskItem("Prep questions")),
            originalRequest = "ptc + prep",
            clarifications = emptyList(),
            createdAt = clock.instant(),
        )
        val channel = channel()

        val next = flow.handleInbound(userId, channel, state, ChannelInbound.Selection(QuickAddFlow.OPTION_SAVE))

        assertNull(next)
        verify(backlogTaskService).createTask(eq(userId), eq(boardId), check {
            assertEquals("Prep questions", it.title)
        })
        verify(oneOffEventService).createEvents(eq(userId), eq(boardId), check<List<OneOffEventDraft>> {
            assertEquals(1, it.size)
            assertEquals("Parent-teacher conference", it[0].title)
        })
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.outcome", "result", "saved").count())
        val sent = channel.drain()
        // One "Added: ..." message; no invite-skipped warning when invitesScheduled is true.
        assertEquals(1, sent.size)
        val saved = assertIs<ChannelMessage.Text>(sent.single())
        assertTrue(saved.text.contains("Parent-teacher conference"))
        assertTrue(saved.text.contains("Prep questions"))
    }

    @Test
    fun `save surfaces an invite-skipped warning when the user has no calendar-invite channel`() {
        val createdEvent = OneOffEvent(
            id = UUID.randomUUID(),
            userId = userId,
            boardId = boardId,
            title = "Flight",
            startsAt = Instant.parse("2026-08-01T05:00:00Z"),
            endsAt = Instant.parse("2026-08-01T09:00:00Z"),
            location = null,
            notes = null,
            icalUid = "uid-2",
            cancelledAt = null,
        )
        whenever(oneOffEventService.createEvents(eq(userId), eq(boardId), any()))
            .thenReturn(CreatedEvents(listOf(createdEvent), invitesScheduled = false))

        val state = QuickAddState.AwaitingConfirmation(
            items = listOf(eventItem(title = "Flight", location = null)),
            originalRequest = "flight",
            clarifications = emptyList(),
            createdAt = clock.instant(),
        )
        val channel = channel()

        flow.handleInbound(userId, channel, state, ChannelInbound.Selection(QuickAddFlow.OPTION_SAVE))

        val sent = channel.drain().filterIsInstance<ChannelMessage.Text>()
        // First message is the "Added: ..." line; second is the invite-skipped warning.
        assertEquals(2, sent.size)
        assertTrue(sent[0].text.contains("Flight"))
        // The warning text comes from messages.properties — non-empty is enough here.
        assertTrue(sent[1].text.isNotBlank())
    }

    @Test
    fun `cancel ends the flow without saving`() {
        val state = QuickAddState.AwaitingConfirmation(
            items = listOf(taskItem()),
            originalRequest = "buy milk",
            clarifications = emptyList(),
            createdAt = clock.instant(),
        )
        val channel = channel()

        val next = flow.handleInbound(userId, channel, state, ChannelInbound.Selection(QuickAddFlow.OPTION_CANCEL))

        assertNull(next)
        verify(backlogTaskService, never()).createTask(any(), any(), any())
        verify(oneOffEventService, never()).createEvents(any(), any(), any())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.outcome", "result", "cancelled").count())
    }

    @Test
    fun `free text on the card is treated as an adjustment`() {
        whenever(suggestionAgent.quickAddRevise(eq(userId), eq("buy milk"), any(), eq("make it oat milk"), any(), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem("Buy oat milk"))))
        val state = QuickAddState.AwaitingConfirmation(
            items = listOf(taskItem()),
            originalRequest = "buy milk",
            clarifications = emptyList(),
            createdAt = clock.instant(),
        )
        val channel = channel()

        val next = flow.handleInbound(userId, channel, state, ChannelInbound.Text("make it oat milk"))

        val confirmation = assertIs<QuickAddState.AwaitingConfirmation>(next)
        val task = assertIs<CapturedItem.Task>(confirmation.items.single())
        assertEquals("Buy oat milk", task.draft.title)
    }

    @Test
    fun `vague input asks a clarifying question, then drafts from the answer`() {
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("fix it"), any(), eq(false), eq(false)))
            .thenReturn(SuggestionOutcome.Clarify("Which area?", listOf(ClarifyOption("home", "Home"), ClarifyOption("work", "Work"))))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem("Fix the kitchen sink"))))
        val channel = channel()

        val clarifying = flow.begin(userId, channel, "fix it")

        val state = assertIs<QuickAddState.AwaitingClarification>(clarifying)
        assertEquals(1, state.rounds)
        val q = assertIs<ChannelMessage.Choice>(channel.drain().single())
        assertEquals(listOf("o0", "o1", QuickAddFlow.OPTION_EXPLAIN), q.options.map { it.id })

        val answered = flow.handleInbound(userId, channel(), state, ChannelInbound.Selection("o0"))

        assertIs<QuickAddState.AwaitingConfirmation>(answered)
        val clarificationsCaptor = argumentCaptor<List<ClarificationExchange>>()
        verify(suggestionAgent, times(2)).quickAddDraft(eq(userId), eq("fix it"), clarificationsCaptor.capture(), eq(false), eq(false))
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
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("fix it"), any(), eq(true), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem("Fix the sink"))))

        val next = flow.handleInbound(userId, channel(), state, ChannelInbound.Text("the kitchen"))

        assertIs<QuickAddState.AwaitingConfirmation>(next)
        verify(suggestionAgent).quickAddDraft(eq(userId), eq("fix it"), any(), eq(true), eq(false))
    }

    @Test
    fun `a photo starts a capture, echoes what was read, and shows the card`() {
        val png = InboundAttachment(AttachmentKind.IMAGE, byteArrayOf(1, 2, 3), "image/jpeg")
        whenever(suggestionAgent.quickAddDraftFromMedia(eq(userId), eq(listOf(png)), anyOrNull(), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(listOf(eventItem()), sourceText = "Party at 4pm on Saturday"))
        val channel = channel()

        val state = flow.beginFromMedia(userId, channel, listOf(png), caption = null)

        val confirmation = assertIs<QuickAddState.AwaitingConfirmation>(state)
        // The model's read-back replaces the typed request, so later rounds need no attachment.
        assertEquals("Party at 4pm on Saturday", confirmation.originalRequest)
        val messages = channel.drain()
        val echo = assertIs<ChannelMessage.Text>(messages.first())
        assertTrue(echo.text.contains("Party at 4pm on Saturday"))
        assertIs<ChannelMessage.Choice>(messages.last())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.media", "kind", "image", "result", "captured").count())
    }

    @Test
    fun `a voice note whose modality the model cannot accept ends the flow with an explanation`() {
        val voice = InboundAttachment(AttachmentKind.AUDIO, byteArrayOf(1), "audio/ogg", format = "ogg")
        whenever(suggestionAgent.quickAddDraftFromMedia(any(), any(), anyOrNull(), any()))
            .thenThrow(UnsupportedModalityException(listOf(AttachmentKind.AUDIO)))
        val channel = channel()

        val next = flow.beginFromMedia(userId, channel, listOf(voice), caption = null)

        assertNull(next)
        val reply = assertIs<ChannelMessage.Text>(channel.drain().single())
        assertTrue(reply.text.contains("voice messages"))
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.media", "kind", "audio", "result", "unsupported").count())
    }

    @Test
    fun `media falls back to its caption when the model reads nothing back`() {
        val png = InboundAttachment(AttachmentKind.IMAGE, byteArrayOf(1), "image/jpeg")
        whenever(suggestionAgent.quickAddDraftFromMedia(any(), any(), anyOrNull(), any()))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem())))
        val channel = channel()

        val state = flow.beginFromMedia(userId, channel, listOf(png), caption = "add this")

        assertEquals("add this", assertIs<QuickAddState.AwaitingConfirmation>(state).originalRequest)
        // No read-back, so nothing is echoed — just the card.
        assertIs<ChannelMessage.Choice>(channel.drain().single())
    }

    @Test
    fun `media sent mid-capture restarts the capture from the attachment`() {
        val png = InboundAttachment(AttachmentKind.IMAGE, byteArrayOf(1), "image/jpeg")
        val state = QuickAddState.AwaitingAdjustment(
            items = listOf(taskItem()),
            originalRequest = "buy milk",
            clarifications = emptyList(),
            createdAt = clock.instant(),
        )
        whenever(suggestionAgent.quickAddDraftFromMedia(any(), any(), anyOrNull(), any()))
            .thenReturn(SuggestionOutcome.Draft(listOf(eventItem()), sourceText = "Party at 4pm"))

        val next = flow.handleInbound(userId, channel(), state, ChannelInbound.Media(listOf(png)))

        assertEquals("Party at 4pm", assertIs<QuickAddState.AwaitingConfirmation>(next).originalRequest)
        verify(suggestionAgent, never()).quickAddRevise(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `unparseable output ends the flow`() {
        whenever(suggestionAgent.quickAddDraft(any(), any(), any(), any(), any()))
            .thenReturn(SuggestionOutcome.Unparseable)
        val channel = channel()

        val next = flow.begin(userId, channel, "asdfgh")

        assertNull(next)
        assertIs<ChannelMessage.Text>(channel.drain().single())
    }

    @Test
    fun `event whose start is in the past is dropped during validation`() {
        // 2026-06-11 is "today" per the fixed clock; this event is well in the past.
        whenever(suggestionAgent.quickAddDraft(any(), any(), any(), any(), any()))
            .thenReturn(SuggestionOutcome.Draft(listOf(eventItem(start = "2025-01-01T10:00:00Z", end = "2025-01-01T11:00:00Z"))))
        val channel = channel()

        val next = flow.begin(userId, channel, "old event")

        // The single dropped event leaves nothing to confirm — flow ends with unparseable.
        assertNull(next)
        assertIs<ChannelMessage.Text>(channel.drain().single())
    }

    @Test
    fun `event with no end time validates with a default 60-minute duration`() {
        whenever(suggestionAgent.quickAddDraft(any(), any(), any(), any(), any()))
            .thenReturn(SuggestionOutcome.Draft(listOf(eventItem(start = "2026-07-15T19:30:00Z", end = null))))
        val channel = channel()

        val state = flow.begin(userId, channel, "ptc")

        val confirmation = assertIs<QuickAddState.AwaitingConfirmation>(state)
        val event = assertIs<CapturedItem.Event>(confirmation.items.single())
        // endIso is populated with start + 60min (formatted with offset).
        assertTrue(event.draft.endIso?.startsWith("2026-07-15T20:30") == true)
    }

    // --- Unprompted messages (docs/FREE-TEXT-CAPTURE.md) ---------------------------------------

    @Test
    fun `an unprompted message captures like add does`() {
        whenever(suggestionAgent.quickAddDraft(eq(userId), eq("buy milk"), any(), eq(false), eq(true)))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem())))
        val channel = channel()

        val entry = flow.beginUnprompted(userId, channel, "buy milk")

        val state = assertIs<CaptureEntry.Captured>(entry).state
        assertIs<QuickAddState.AwaitingConfirmation>(state)
        assertIs<ChannelMessage.Choice>(channel.drain().single())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.entry", "source", "unprompted").count())
    }

    @Test
    fun `an unprompted message the model calls not-a-capture is routed, not drafted`() {
        whenever(suggestionAgent.quickAddDraft(any(), any(), any(), any(), eq(true)))
            .thenReturn(SuggestionOutcome.NotACapture(CaptureIntent.CURRENT))
        val channel = channel()

        val entry = flow.beginUnprompted(userId, channel, "what's on for today?")

        assertEquals(CaptureIntent.CURRENT, assertIs<CaptureEntry.Routed>(entry).intent)
        // The channel decides what to say for a routed message; the flow says nothing.
        assertTrue(channel.drain().isEmpty())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.route", "intent", "current").count())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.outcome", "result", "not_a_capture").count())
    }

    @Test
    fun `the not-a-capture shape is never offered to add or to a later round of a live capture`() {
        whenever(suggestionAgent.quickAddDraft(any(), any(), any(), any(), any()))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem())))
        whenever(suggestionAgent.quickAddRevise(any(), any(), any(), any(), any(), any()))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem())))

        val state = flow.begin(userId, channel(), "buy milk")
        verify(suggestionAgent).quickAddDraft(eq(userId), eq("buy milk"), any(), eq(false), eq(false))

        // Adjusting the draft revises it — there is no route out of a capture the user is fixing.
        flow.handleInbound(userId, channel(), state!!, ChannelInbound.Text("make it two"))
        verify(suggestionAgent).quickAddRevise(eq(userId), any(), any(), eq("make it two"), any(), eq(false))
    }

    @Test
    fun `an unprompted voice note may be routed, but an unprompted photo is always a capture`() {
        val voice = InboundAttachment(AttachmentKind.AUDIO, byteArrayOf(1), "audio/ogg", format = "ogg")
        whenever(suggestionAgent.quickAddDraftFromMedia(any(), eq(listOf(voice)), anyOrNull(), eq(true)))
            .thenReturn(SuggestionOutcome.NotACapture(CaptureIntent.PLAN))

        val entry = flow.beginUnpromptedFromMedia(userId, channel(), listOf(voice), caption = null)

        assertEquals(CaptureIntent.PLAN, assertIs<CaptureEntry.Routed>(entry).intent)

        // The same unprompted entry point, but a photo: the shape is not offered at all, so there
        // is no way for the model to decline an image it should just be reading.
        val png = InboundAttachment(AttachmentKind.IMAGE, byteArrayOf(1), "image/jpeg")
        whenever(suggestionAgent.quickAddDraftFromMedia(any(), eq(listOf(png)), anyOrNull(), eq(false)))
            .thenReturn(SuggestionOutcome.Draft(listOf(taskItem())))

        val photoEntry = flow.beginUnpromptedFromMedia(userId, channel(), listOf(png), caption = null)

        assertIs<QuickAddState.AwaitingConfirmation>(assertIs<CaptureEntry.Captured>(photoEntry).state)
    }

    @Test
    fun `a rate-limited capture says so and makes no model call`() {
        rateLimiter = RateLimiter { false }
        val channel = channel()

        val entry = flow.beginUnprompted(userId, channel, "buy milk")

        assertNull(assertIs<CaptureEntry.Captured>(entry).state)
        assertIs<ChannelMessage.Text>(channel.drain().single())
        verify(suggestionAgent, never()).quickAddDraft(any(), any(), any(), any(), any())
        assertEquals(1.0, meterRegistry.counter("tasker.quickadd.outcome", "result", "rate_limited").count())
    }

    @Test
    fun `a bare add costs nothing against the rate limit`() {
        rateLimiter = RateLimiter { false }
        val channel = channel()

        // No description yet, so no model call to charge for — the limit applies to the draft that
        // follows, not to being asked what to add.
        assertIs<QuickAddState.AwaitingDescription>(flow.begin(userId, channel, null))
        assertIs<ChannelMessage.Text>(channel.drain().single())
    }
}
