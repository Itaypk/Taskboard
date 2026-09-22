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
     */
    data object Empty : TurnOutcome
}

data class RequestedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
