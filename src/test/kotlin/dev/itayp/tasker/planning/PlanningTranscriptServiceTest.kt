package dev.itayp.tasker.planning

import dev.itayp.nescioquid.openrouter.FunctionCallDetails
import dev.itayp.nescioquid.openrouter.ToolCall
import dev.itayp.tasker.ai.conversation.ConversationService
import dev.itayp.tasker.ai.conversation.StoredMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class PlanningTranscriptServiceTest {

    @Mock lateinit var conversationService: ConversationService

    private val objectMapper = jacksonObjectMapper()
    private val service by lazy { PlanningTranscriptService(conversationService, objectMapper) }

    private val conversationId = UUID.randomUUID()
    private var pos = 0

    @Test
    fun `reconstruct maps a full conversation to a visible transcript`() {
        whenever(conversationService.getMessages(conversationId)).thenReturn(
            listOf(
                user("Let's plan the week (kickoff)"),                              // pos 0 — skipped
                assistantToolCall("say", mapOf("text" to "Welcome back!", "suggested_replies" to listOf("Sure"))),
                toolResult("say", """{"ok":true}"""),                              // ack — skipped
                assistantToolCall("ask_choice", mapOf(
                    "prompt" to "Which day?",
                    "options" to listOf(mapOf("id" to "mon", "label" to "Monday"), mapOf("id" to "tue", "label" to "Tuesday")),
                )),
                toolResult("ask_choice", """{"choice_id":"mon","label":"Monday","free_text":null}"""),
                assistantToolCall("find_task", mapOf("query" to "report")),         // internal — skipped
                toolResult("find_task", """{"matches":[]}"""),                     // internal — skipped
                user("Add a 30 min run"),                                          // free-text user reply
                assistantToolCall("submit_plan", mapOf("message" to "All set — have a great week!", "summary" to "Planned.")),
                toolResult("submit_plan", """{"ok":true}"""),                      // ack — skipped
            )
        )

        val transcript = service.reconstruct(conversationId)

        assertEquals(5, transcript.size)

        assertEquals("assistant", transcript[0].role)
        assertEquals("text", transcript[0].type)
        assertEquals("Welcome back!", transcript[0].text)
        assertEquals(listOf("Sure"), transcript[0].completions)

        assertEquals("assistant", transcript[1].role)
        assertEquals("choice", transcript[1].type)
        assertEquals("Which day?", transcript[1].text)
        assertEquals(listOf("mon", "tue"), transcript[1].options.map { it.id })
        assertEquals(listOf("Monday", "Tuesday"), transcript[1].options.map { it.label })

        assertEquals("user", transcript[2].role)
        assertEquals("Monday", transcript[2].text)   // from the choice result's label

        assertEquals("user", transcript[3].role)
        assertEquals("Add a 30 min run", transcript[3].text)

        assertEquals("assistant", transcript[4].role)
        assertEquals("text", transcript[4].type)
        assertEquals("All set — have a great week!", transcript[4].text)
    }

    @Test
    fun `submit_plan closing is suppressed when say spoke in the same turn`() {
        whenever(conversationService.getMessages(conversationId)).thenReturn(
            listOf(
                user("kickoff"),
                assistantToolCalls(
                    "say" to mapOf("text" to "Here's your plan."),
                    "submit_plan" to mapOf("message" to "Farewell!", "summary" to "Planned."),
                ),
            )
        )

        val transcript = service.reconstruct(conversationId)

        assertEquals(1, transcript.size)
        assertEquals("Here's your plan.", transcript[0].text)
    }

    @Test
    fun `submit_plan falls back to summary when message is blank`() {
        whenever(conversationService.getMessages(conversationId)).thenReturn(
            listOf(
                user("kickoff"),
                assistantToolCall("submit_plan", mapOf("message" to "", "summary" to "Locked in your plan.")),
            )
        )

        val transcript = service.reconstruct(conversationId)

        assertEquals(1, transcript.size)
        assertEquals("Locked in your plan.", transcript[0].text)
    }

    @Test
    fun `choice result without a label falls back to free text`() {
        whenever(conversationService.getMessages(conversationId)).thenReturn(
            listOf(
                user("kickoff"),
                toolResult("ask_choice", """{"choice_id":null,"label":null,"free_text":"some other day"}"""),
            )
        )

        val transcript = service.reconstruct(conversationId)

        assertEquals(1, transcript.size)
        assertEquals("user", transcript[0].role)
        assertEquals("some other day", transcript[0].text)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun user(content: String) =
        stored(role = "user", content = content)

    private fun assistantToolCall(name: String, args: Map<String, Any?>) =
        stored(role = "assistant", toolCallsJson = toolCallsJson(name to args))

    private fun assistantToolCalls(vararg calls: Pair<String, Map<String, Any?>>) =
        stored(role = "assistant", toolCallsJson = toolCallsJson(*calls))

    private fun toolResult(toolName: String, content: String) =
        stored(role = "tool", content = content, toolName = toolName, toolCallId = UUID.randomUUID().toString())

    private fun stored(
        role: String,
        content: String? = null,
        toolCallsJson: String? = null,
        toolName: String? = null,
        toolCallId: String? = null,
    ) = StoredMessage(
        id = UUID.randomUUID(),
        conversationId = conversationId,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        position = pos++,
    )

    private fun toolCallsJson(vararg calls: Pair<String, Map<String, Any?>>): String {
        val toolCalls = calls.map { (name, args) ->
            ToolCall(
                id = UUID.randomUUID().toString(),
                function = FunctionCallDetails(name = name, arguments = objectMapper.writeValueAsString(args)),
            )
        }
        return objectMapper.writeValueAsString(toolCalls)
    }
}
