package dev.itayp.tasker.ai

/**
 * What an LLM turn produced. Either the model emitted plain text content (no tool
 * calls — discouraged but possible) or it emitted one or more tool calls for the
 * orchestrator to dispatch. The orchestrator is responsible for executing the
 * appropriate side effects, recording `tool_result`s, and deciding whether to
 * re-invoke the model via [AiConversationManager.continueConversation].
 */
sealed interface TurnOutcome {
    data class TextReply(val text: String) : TurnOutcome
    data class ToolCalls(val calls: List<RequestedToolCall>) : TurnOutcome
}

data class RequestedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
