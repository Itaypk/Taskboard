package dev.itayp.tasker.ai.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

// ── Request ──────────────────────────────────────────────────────────────────

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    @JsonProperty("tool_choice") val toolChoice: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @JsonProperty("tool_calls") val toolCalls: List<ToolCall>? = null,
    @JsonProperty("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDetails,
)

data class FunctionCallDetails(
    val name: String,
    val arguments: String,
)

// ── Response ─────────────────────────────────────────────────────────────────

data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage?,
)

data class Choice(
    val message: ChatMessage,
    @JsonProperty("finish_reason") val finishReason: String,
)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
)
