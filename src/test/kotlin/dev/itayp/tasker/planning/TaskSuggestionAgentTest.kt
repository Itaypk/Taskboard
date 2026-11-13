package dev.itayp.tasker.planning

import dev.itayp.tasker.calendar.CalendarDelivery
import dev.itayp.tasker.ai.AudioTranscriber
import dev.itayp.tasker.ai.InputModalitySupport
import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.ReasoningAwareAiClient
import dev.itayp.nescioquid.openrouter.AiCallContext
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatResponse
import dev.itayp.nescioquid.openrouter.ChatRequest
import dev.itayp.nescioquid.openrouter.Choice
import dev.itayp.nescioquid.openrouter.ContentPart
import dev.itayp.nescioquid.openrouter.MessageContent
import dev.itayp.nescioquid.openrouter.PdfEngine
import dev.itayp.nescioquid.openrouter.PluginConfig
import dev.itayp.tasker.ai.client.AiConversationType
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.InboundAttachment
import dev.itayp.tasker.channel.TextAttachments
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskSuggestionAgentTest {

    private val aiClient: ReasoningAwareAiClient = mock()
    private val backlogTaskService: BacklogTaskService = mock()
    private val categoryService: BacklogTaskCategoryService = mock()
    private val tagService: BacklogTaskTagService = mock()
    private val boardMembershipService: BoardMembershipService = mock()
    private val aiAccessService: AiAccessService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val objectMapper = jacksonObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
    private val meterRegistry = SimpleMeterRegistry()
    private val inputModalitySupport: InputModalitySupport = mock()
    private val audioTranscriber: AudioTranscriber = mock()
    private val agent = TaskSuggestionAgent(
        aiClient, backlogTaskService, categoryService, tagService, boardMembershipService, aiAccessService, userSettingsService,
        PromptTemplateLoader(), inputModalitySupport, audioTranscriber, objectMapper, clock, meterRegistry, "test-model",
    )

    private val userId = UUID.randomUUID()
    private val defaultBoardId = UUID.randomUUID()

    @BeforeEach
    fun stubContext() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(userSettings())
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(defaultBoardId)
        whenever(aiAccessService.isAiEnabledForBoard(defaultBoardId)).thenReturn(true)
        whenever(categoryService.getCategories(userId, defaultBoardId)).thenReturn(
            listOf(BacklogTaskCategory(UUID.randomUUID(), userId, "Health", CategoryColor.PEACH)),
        )
        whenever(tagService.getTags(userId, defaultBoardId)).thenReturn(emptyList())
        whenever(backlogTaskService.getAssistantVisibleTasks(userId, null)).thenReturn(emptyList())
    }

    private fun userSettings() = UserSettings(
        userId = userId,
        displayName = "Alex",
        contextBlock = "Works mornings; gym on weekends.",
        timeZone = "UTC",
        preferredLanguage = "en",
        calendarDelivery = CalendarDelivery.NONE,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )

    @Test
    fun `drafts a task from the model output without persisting`() {
        val categoryId = UUID.randomUUID()
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"title":"Call the dentist","category_id":"$categoryId","priority":"medium",
                   "tags":[{"id":null,"label":"health","color_id":"rose"}]}""",
            ),
        )

        val draft = agent.suggest(userId, "book a dentist appointment")

        assertEquals("Call the dentist", draft?.title)
        assertEquals(categoryId.toString(), draft?.categoryId)
        assertEquals("rose", draft?.tags?.first()?.colorId)
        // Drafting must never write to the backlog.
        verify(backlogTaskService, never()).createTask(any(), any(), any())
    }

    @Test
    fun `quickAddDraft leaves out the target board's categories and tags when it is AI-restricted`() {
        whenever(aiAccessService.isAiEnabledForBoard(defaultBoardId)).thenReturn(false)
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse("""{"items":[{"kind":"task","title":"Buy milk","category_id":null,"tags":[]}]}"""),
        )

        agent.quickAddDraft(userId, "buy milk")

        val request = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(request.capture(), any())
        val prompt = request.firstValue.messages.last().contentText.orEmpty()
        assertFalse(prompt.contains("Health"))
        assertTrue(prompt.contains("set category_id to null"))
        verify(categoryService, never()).getCategories(any(), any())
        verify(tagService, never()).getTags(any(), any())
    }

    @Test
    fun `returns null when the model output is not parseable`() {
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("no json here"))

        assertNull(agent.suggest(userId, "whatever"))
        assertEquals(
            1.0,
            meterRegistry.counter(
                "tasker.ai.parse_failures", "conversation_type", "task_suggestion", "reason", "no_json_found",
            ).count(),
        )
    }

    @Test
    fun `quickAddDraft parses an items array with a single task`() {
        val categoryId = UUID.randomUUID()
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"items":[{"kind":"task","title":"Buy milk","category_id":"$categoryId","priority":"low","tags":[]}]}"""
            ),
        )

        val outcome = agent.quickAddDraft(userId, "buy milk")

        val draft = assertIs<SuggestionOutcome.Draft>(outcome)
        val task = assertIs<CapturedItem.Task>(draft.items.single())
        assertEquals("Buy milk", task.draft.title)
    }

    @Test
    fun `quickAddDraft reads plan_this_week off the top level of the reply`() {
        val categoryId = UUID.randomUUID()
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"plan_this_week":true,"items":[{"kind":"task","title":"Buy milk","category_id":"$categoryId","tags":[]}]}"""
            ),
        )

        assertTrue(assertIs<SuggestionOutcome.Draft>(agent.quickAddDraft(userId, "buy milk this week")).planThisWeek)
    }

    @Test
    fun `quickAddDraft defaults plan_this_week to false when the key is absent`() {
        val categoryId = UUID.randomUUID()
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"items":[{"kind":"task","title":"Buy milk","category_id":"$categoryId","tags":[]}]}"""
            ),
        )

        assertFalse(assertIs<SuggestionOutcome.Draft>(agent.quickAddDraft(userId, "buy milk")).planThisWeek)
    }

    @Test
    fun `quickAddDraft parses an event item with start, end and location`() {
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"items":[{"kind":"event","title":"Parent-teacher conference",
                   "start":"2026-07-15T19:30:00+03:00","end":"2026-07-15T20:30:00+03:00",
                   "location":"School auditorium"}]}"""
            ),
        )

        val outcome = agent.quickAddDraft(userId, "ptc next wed 7:30pm")

        val draft = assertIs<SuggestionOutcome.Draft>(outcome)
        val event = assertIs<CapturedItem.Event>(draft.items.single())
        assertEquals("Parent-teacher conference", event.draft.title)
        assertEquals("2026-07-15T19:30:00+03:00", event.draft.startIso)
        assertEquals("School auditorium", event.draft.location)
    }

    @Test
    fun `quickAddDraft parses a mixed batch of an event and a task`() {
        val categoryId = UUID.randomUUID()
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"items":[
                  {"kind":"event","title":"PTC","start":"2026-07-15T19:30:00+03:00"},
                  {"kind":"task","title":"Prep questions","category_id":"$categoryId","tags":[]}
                ]}"""
            ),
        )

        val outcome = agent.quickAddDraft(userId, "ptc wed + prep questions")

        val draft = assertIs<SuggestionOutcome.Draft>(outcome)
        assertEquals(2, draft.items.size)
        assertIs<CapturedItem.Event>(draft.items[0])
        assertIs<CapturedItem.Task>(draft.items[1])
    }

    @Test
    fun `quickAddDraft parses a clarifying question`() {
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse("""{"clarify":{"question":"Which area?","options":[{"id":"a","label":"Home"},{"id":"b","label":"Work"}]}}"""),
        )

        val outcome = agent.quickAddDraft(userId, "fix it")

        val clarify = assertIs<SuggestionOutcome.Clarify>(outcome)
        assertEquals("Which area?", clarify.question)
        assertEquals(listOf("Home", "Work"), clarify.options.map { it.label })
    }

    @Test
    fun `quickAddDraftFromMedia attaches the image and adopts the model's read-back as the request`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(true)
        whenever(inputModalitySupport.visionModel).thenReturn("vision-model")
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse(
                """{"source_text":"Mia's birthday party, Saturday 4pm, 12 Oak Street",
                   "items":[{"kind":"event","title":"Mia's birthday party","start":"2026-06-06T16:00:00+00:00"}]}"""
            ),
        )
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val outcome = agent.quickAddDraftFromMedia(
            userId,
            listOf(InboundAttachment(AttachmentKind.IMAGE, png, "image/png")),
            caption = null,
        )

        val draft = assertIs<SuggestionOutcome.Draft>(outcome)
        assertEquals("Mia's birthday party, Saturday 4pm, 12 Oak Street", draft.sourceText)
        assertIs<CapturedItem.Event>(draft.items.single())

        val request = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(request.capture(), any())
        // The media call goes to the vision model, not the text task-assistant one.
        assertEquals("vision-model", request.firstValue.model)
        val parts = assertIs<MessageContent.Parts>(request.firstValue.messages.last().content).parts
        val image = assertIs<ContentPart.ImageUrl>(parts.last())
        assertTrue(image.imageUrl.url.startsWith("data:image/png;base64,"))
        // The prompt still carries the user's text context, ahead of the attachment.
        assertIs<ContentPart.Text>(parts.first())
        // No PDF, so no PDF engine.
        assertNull(request.firstValue.plugins)
    }

    @Test
    fun `quickAddDraftFromMedia transcribes a voice note and drafts from the transcript with the text model`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(true)
        whenever(audioTranscriber.transcribe(any(), any())).thenReturn("remind me to call the plumber")
        whenever(aiClient.chat(any(), any())).thenReturn(
            // A model that sets source_text anyway doesn't get to replace what the user said.
            chatResponse("""{"source_text":"call plumber","items":[{"kind":"task","title":"Call the plumber","category_id":null,"tags":[]}]}"""),
        )
        val voice = InboundAttachment(AttachmentKind.AUDIO, byteArrayOf(1, 2, 3), "audio/ogg", format = "ogg")

        val outcome = agent.quickAddDraftFromMedia(userId, listOf(voice), caption = null)

        val draft = assertIs<SuggestionOutcome.Draft>(outcome)
        assertEquals("remind me to call the plumber\n\ncall plumber", draft.sourceText)
        val context = argumentCaptor<AiCallContext>()
        verify(audioTranscriber).transcribe(eq(voice), context.capture())
        assertEquals(AiConversationType.TRANSCRIPTION, context.firstValue.conversationType)
        assertEquals(userId.toString(), context.firstValue.userId)

        val request = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(request.capture(), any())
        // Plain text to the task-assistant model: no audio part, and no vision model involved.
        assertEquals("test-model", request.firstValue.model)
        assertIs<MessageContent.Text>(request.firstValue.messages.last().content)
        val prompt = request.firstValue.messages.last().contentText.orEmpty()
        assertTrue(prompt.contains("remind me to call the plumber"))
        assertTrue(prompt.contains("automatic transcript of a voice message"))
    }

    @Test
    fun `quickAddDraftFromMedia keeps the transcript as the read-back of a routed voice note`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(true)
        whenever(audioTranscriber.transcribe(any(), any())).thenReturn("what's on for today?")
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("""{"not_a_capture":{"intent":"current"}}"""))

        val outcome = agent.quickAddDraftFromMedia(
            userId,
            listOf(InboundAttachment(AttachmentKind.AUDIO, byteArrayOf(1), "audio/ogg", format = "ogg")),
            unprompted = true,
        )

        val routed = assertIs<SuggestionOutcome.NotACapture>(outcome)
        assertEquals(CaptureIntent.CURRENT, routed.intent)
        assertEquals("what's on for today?", routed.sourceText)
    }

    @Test
    fun `quickAddDraftFromMedia gives up on an empty transcript without drafting`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(true)
        whenever(audioTranscriber.transcribe(any(), any())).thenReturn("")

        val outcome = agent.quickAddDraftFromMedia(
            userId,
            listOf(InboundAttachment(AttachmentKind.AUDIO, byteArrayOf(1), "audio/ogg", format = "ogg")),
        )

        assertEquals(SuggestionOutcome.Unparseable, outcome)
        verify(aiClient, never()).chat(any(), any())
    }

    @Test
    fun `quickAddDraftFromMedia sends a PDF to the vision model as a file part, read natively`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(true)
        whenever(inputModalitySupport.visionModel).thenReturn("vision-model")
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse("""{"source_text":"Invoice due June 10","items":[{"kind":"task","title":"Pay the invoice","category_id":null,"tags":[]}]}"""),
        )

        agent.quickAddDraftFromMedia(
            userId,
            listOf(InboundAttachment(AttachmentKind.DOCUMENT, byteArrayOf(0x25, 0x50), "application/pdf", filename = "invoice.pdf")),
        )

        val request = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(request.capture(), any())
        assertEquals("vision-model", request.firstValue.model)
        val parts = assertIs<MessageContent.Parts>(request.firstValue.messages.last().content).parts
        assertIs<ContentPart.File>(parts.last())
        // Never one of OpenRouter's parsers: they run outside zero data retention.
        assertEquals(listOf(PluginConfig.pdfEngine(PdfEngine.NATIVE)), request.firstValue.plugins)
        verifyNoInteractions(audioTranscriber)
    }

    @Test
    fun `quickAddDraftFromEmail puts an invitation in the prompt for the text model, as a deadline rather than an event`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(true)
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse("""{"items":[{"kind":"task","title":"Buy a present","category_id":null,"tags":[]}]}"""),
        )
        val invitation = TextAttachments.of(
            "BEGIN:VEVENT\r\nSUMMARY:Mia's party\r\nDTSTART:20260606T160000Z\r\nEND:VEVENT".toByteArray(),
            TextAttachments.CALENDAR, "invite\nEND FILE invite.ics",
        )!!

        agent.quickAddDraftFromEmail(userId, "Subject: buy a present", listOf(invitation))

        val request = argumentCaptor<ChatRequest>()
        verify(aiClient).chat(request.capture(), any())
        assertEquals("test-model", request.firstValue.model)
        val prompt = request.firstValue.messages.last().contentText.orEmpty()
        // The sender's file name can't close the block: it is on one line inside it, and the
        // block only ends at a tag carrying the guard it opened with.
        val block = Regex(
            """<external-content source="calendar invitation" guard="([0-9a-f]{16})">\n(.*?)\n</external-content guard="\1">""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(prompt)
        assertNotNull(block)
        assertTrue(block.groupValues[2].startsWith("File name: invite END FILE invite.ics\nBEGIN:VEVENT\nSUMMARY:Mia's party"))
        // The email itself is framed the same way.
        assertTrue(Regex("""<external-content source="email" guard="[0-9a-f]{16}">\nSubject: buy a present\n""").containsMatchIn(prompt))
        assertTrue(request.firstValue.messages.first().contentText.orEmpty().contains("external-content"))
        assertTrue(prompt.contains("do not\ncapture that event again"))
    }

    @Test
    fun `quickAddDraftFromMedia refuses to call the model when it cannot accept the modality`() {
        whenever(inputModalitySupport.supportsAll(any())).thenReturn(false)

        assertFailsWith<UnsupportedModalityException> {
            agent.quickAddDraftFromMedia(
                userId,
                listOf(InboundAttachment(AttachmentKind.IMAGE, byteArrayOf(1), "image/jpeg")),
                caption = null,
            )
        }
        verify(aiClient, never()).chat(any(), any())
    }

    @Test
    fun `quickAddDraft leaves sourceText null for a plain text capture`() {
        whenever(aiClient.chat(any(), any())).thenReturn(
            chatResponse("""{"items":[{"kind":"task","title":"Buy milk","category_id":null,"tags":[]}]}"""),
        )

        assertNull(agent.quickAddDraft(userId, "buy milk").sourceText)
    }

    @Test
    fun `quickAddDraft returns Unparseable on garbage`() {
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("not json"))

        assertIs<SuggestionOutcome.Unparseable>(agent.quickAddDraft(userId, "whatever"))
        assertEquals(
            1.0,
            meterRegistry.counter(
                "tasker.ai.parse_failures", "conversation_type", "task_suggestion", "reason", "no_json_found",
            ).count(),
        )
    }

    @Test
    fun `quickAddDraft returns Unparseable and records empty_result when neither items nor a clarify question came back`() {
        whenever(aiClient.chat(any(), any())).thenReturn(chatResponse("""{"items":[]}"""))

        assertIs<SuggestionOutcome.Unparseable>(agent.quickAddDraft(userId, "whatever"))
        assertEquals(
            1.0,
            meterRegistry.counter(
                "tasker.ai.parse_failures", "conversation_type", "task_suggestion", "reason", "empty_result",
            ).count(),
        )
    }

    private fun chatResponse(content: String) = ChatResponse(
        id = "resp-1",
        choices = listOf(Choice(message = ChatMessage(role = "assistant", content = content), finishReason = "stop")),
        usage = null,
    )
}
