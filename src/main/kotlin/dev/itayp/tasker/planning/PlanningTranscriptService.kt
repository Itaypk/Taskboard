package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.itayp.nescioquid.openrouter.ToolCall
import dev.itayp.tasker.ai.conversation.ConversationService
import dev.itayp.tasker.ai.conversation.StoredMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.controller.TranscriptMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Rebuilds a user-visible chat transcript from a persisted planning conversation, so the web client
 * can restore the conversation after a page reload (phase 2a). Read-only: it maps stored messages
 * back to the same shapes the live channel would have rendered, mirroring [WeeklyPlanningOrchestrator]:
 *
 *  - assistant `say`         → assistant text (+ suggested replies)
 *  - assistant `ask_choice`  → assistant choice (prompt + options)
 *  - assistant `submit_plan` → assistant text (the closing message), unless a `say` already spoke
 *    in the same turn (matches `renderClosingMessage`'s `!sayRendered` guard)
 *  - tool result for `ask_choice` → the user's selection, as a user text bubble
 *  - free-text user messages → user text bubbles
 *
 * The position-0 message (the internal kickoff prompt) and all data-lookup tool calls/results
 * (`find_task`, `create_task`, …) and one-way acks are internal scaffolding and are skipped. The
 * capacity question/answer are not part of the conversation (see [WeeklyPlanningOrchestrator.start]),
 * so a reconstructed transcript begins at the first real assistant message.
 */
@Service
class PlanningTranscriptService(
    private val conversationService: ConversationService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(PlanningTranscriptService::class.java)

    fun reconstruct(conversationId: UUID): List<TranscriptMessage> {
        val out = mutableListOf<TranscriptMessage>()
        for (msg in conversationService.getMessages(conversationId)) {
            when (msg.role) {
                ROLE_USER -> {
                    if (msg.position == 0) continue // internal kickoff prompt
                    msg.content?.takeIf { it.isNotBlank() }
                        ?.let { out += TranscriptMessage(ROLE_USER, "text", it) }
                }
                ROLE_ASSISTANT -> out += renderAssistant(msg)
                ROLE_TOOL -> renderToolResult(msg)?.let { out += it }
            }
        }
        return out
    }

    private fun renderAssistant(msg: StoredMessage): List<TranscriptMessage> {
        val toolCallsJson = msg.toolCallsJson
        if (toolCallsJson.isNullOrBlank()) {
            // Model spoke as plain text without a tool — the orchestrator renders this as text too.
            return msg.content?.takeIf { it.isNotBlank() }
                ?.let { listOf(TranscriptMessage(ROLE_ASSISTANT, "text", it)) }
                ?: emptyList()
        }
        val calls = runCatching { objectMapper.readValue(toolCallsJson, Array<ToolCall>::class.java).toList() }
            .getOrElse {
                log.warn("Transcript: could not parse stored tool calls: {}", it.message)
                return emptyList()
            }

        val rendered = mutableListOf<TranscriptMessage>()
        var sawSay = false
        for (call in calls) {
            when (call.function.name) {
                SAY -> parse(call.function.arguments, SayArgs::class.java)?.let { args ->
                    sawSay = true
                    rendered += TranscriptMessage(
                        ROLE_ASSISTANT, "text", args.text, args.suggestedReplies ?: emptyList(),
                    )
                }
                ASK_CHOICE -> parse(call.function.arguments, AskChoiceArgs::class.java)?.let { args ->
                    rendered += TranscriptMessage(
                        ROLE_ASSISTANT, "choice", args.prompt,
                        options = args.options.map { ChoiceOption(it.id, it.label) },
                    )
                }
                SUBMIT_PLAN -> if (!sawSay) {
                    parse(call.function.arguments, SubmitPlanArgs::class.java)?.let { args ->
                        (args.message?.takeIf { it.isNotBlank() } ?: args.summary?.takeIf { it.isNotBlank() })
                            ?.let { rendered += TranscriptMessage(ROLE_ASSISTANT, "text", it) }
                    }
                }
                // find_task / create_task / suggest_task / update_task: internal, no transcript entry.
            }
        }
        return rendered
    }

    private fun renderToolResult(msg: StoredMessage): TranscriptMessage? {
        if (msg.toolName != ASK_CHOICE) return null // only the user's choice answer is visible
        val parsed = parse(msg.content ?: return null, ChoiceResult::class.java) ?: return null
        val text = parsed.label?.takeIf { it.isNotBlank() }
            ?: parsed.freeText?.takeIf { it.isNotBlank() }
            ?: parsed.choiceId?.takeIf { it.isNotBlank() }
            ?: return null
        return TranscriptMessage(ROLE_USER, "text", text)
    }

    private fun <T> parse(json: String, type: Class<T>): T? =
        runCatching { objectMapper.readValue(json, type) }
            .onFailure { log.warn("Transcript: failed to parse {}: {}", type.simpleName, it.message) }
            .getOrNull()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SayArgs(
        val text: String = "",
        @JsonProperty("suggested_replies") val suggestedReplies: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AskChoiceArgs(
        val prompt: String = "",
        val options: List<OptionArg> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OptionArg(val id: String = "", val label: String = "")

    // Real submit_plan arguments also carry `tasks`; we only need the closing message/summary.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SubmitPlanArgs(val message: String? = null, val summary: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChoiceResult(
        @JsonProperty("choice_id") val choiceId: String? = null,
        val label: String? = null,
        @JsonProperty("free_text") val freeText: String? = null,
    )

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"
        private const val SAY = "say"
        private const val ASK_CHOICE = "ask_choice"
        private const val SUBMIT_PLAN = "submit_plan"
    }
}
