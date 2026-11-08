package dev.itayp.tasker.ai

/**
 * What an LLM turn produced. Either the model emitted plain text content (no tool
 * calls — discouraged but possible), it emitted one or more tool calls for the
 * orchestrator to dispatch, or the turn came back [Empty]. The orchestrator is
 * responsible for executing the appropriate side effects, recording `tool_result`s,
 * and deciding whether to re-invoke the model via
 * [AiConversationManager.continueConversation].
 */
sealed interface TurnOutcome {
    data class TextReply(val text: String) : TurnOutcome
    data class ToolCalls(val calls: List<RequestedToolCall>) : TurnOutcome

    /**
     * The model produced neither content nor tool calls. Seen in production as an OpenRouter 200
     * carrying an empty assistant message and zeroed usage — a provider-side failure (e.g. a model
     * that emitted a tool call it could not serialize) rather than a deliberate silence.
     *
     * Distinct from a blank [TextReply] because it is not a reply at all: nothing was appended to
     * the transcript, so the caller can re-issue the identical request, and a caller that ends its
     * turn here leaves the user staring at nothing.
     *
     * [malformedToolCall] is set when the provider says why: the model tried to call a tool and
     * produced a call it could not parse (Gemini's `MALFORMED_FUNCTION_CALL`). That is the model's
     * own mistake rather than a transient fault, so re-issuing the identical request tends to
     * reproduce it; [AiConversationManager.continueAfterMalformedToolCall] retries with a nudge.
     */
    data class Empty(val malformedToolCall: Boolean) : TurnOutcome
}

data class RequestedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
