package dev.itayp.tasker.planning

import dev.itayp.tasker.ai.ReasoningAwareAiClient
import dev.itayp.nescioquid.openrouter.ChatMessage
import dev.itayp.nescioquid.openrouter.ChatResponse
import dev.itayp.nescioquid.openrouter.Choice
import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.service.BacklogTaskCategoryService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskSuggestionAgentTest {

    private val aiClient: ReasoningAwareAiClient = mock()
    private val backlogTaskService: BacklogTaskService = mock()
    private val categoryService: BacklogTaskCategoryService = mock()
    private val tagService: BacklogTaskTagService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val objectMapper = jacksonObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
    private val meterRegistry = SimpleMeterRegistry()
    private val agent = TaskSuggestionAgent(
        aiClient, backlogTaskService, categoryService, tagService, userSettingsService,
        PromptTemplateLoader(), objectMapper, clock, meterRegistry, "test-model",
    )

    private val userId = UUID.randomUUID()

    @BeforeEach
    fun stubContext() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(userSettings())
        whenever(categoryService.getAllForUser(userId)).thenReturn(
            listOf(BacklogTaskCategory(UUID.randomUUID(), userId, "Health", CategoryColor.PEACH)),
        )
        whenever(tagService.getAllForUser(userId)).thenReturn(emptyList())
        whenever(backlogTaskService.getTasksAcrossBoards(userId, null)).thenReturn(emptyList())
    }

    private fun userSettings() = UserSettings(
        userId = userId,
        displayName = "Alex",
        contextBlock = "Works mornings; gym on weekends.",
        timeZone = "UTC",
        preferredLanguage = "en",
        calendarInviteEmail = false,
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
