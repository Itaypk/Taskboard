package dev.itayp.tasker.ai.client

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

// ── Request ──────────────────────────────────────────────────────────────────

data class ProviderPreferences(
    val zdr: Boolean = true,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    @JsonProperty("tool_choice") val toolChoice: String? = null,
    val provider: ProviderPreferences = ProviderPreferences(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatMessage(
    val role: String,
    // Either a plain `String` (the common case, and the only form we ever receive back) or a
    // `List<ContentPart>` — the OpenAI/OpenRouter content-array form we send when a message needs a
    // prompt-caching `cache_control` breakpoint. Kept as `Any?` because the wire schema is a true
    // union; use [contentText] to read the string form off a response.
    val content: Any? = null,
    @JsonProperty("tool_calls") val toolCalls: List<ToolCall>? = null,
    @JsonProperty("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    /**
     * Content as a plain string. Responses always carry string content, so read sites use this to
     * stay type-safe; returns null when the content is the structured array form (which we only ever
     * build for outgoing requests).
     */
    @get:JsonIgnore
    val contentText: String?
        get() = content as? String

    companion object {
        /**
         * A message whose content is a single text part marked as a prompt-caching breakpoint
         * (`cache_control: {type: ephemeral}`). OpenRouter forwards the breakpoint to providers with
         * explicit caching (Anthropic, Gemini Flash Lite, …) and harmlessly ignores it for providers
         * that cache automatically. Use for the stable prefix — typically the system prompt.
         */
        fun cacheable(role: String, text: String): ChatMessage =
            ChatMessage(role = role, content = listOf(ContentPart(text = text, cacheControl = CacheControl())))
    }
}

// A single part of the content-array form. Only `text` parts are used today.
data class ContentPart(
    val type: String = "text",
    val text: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("cache_control") val cacheControl: CacheControl? = null,
)

// OpenRouter prompt-caching breakpoint marker; `ephemeral` is the only supported type.
data class CacheControl(
    val type: String = "ephemeral",
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
    // OpenRouter echoes the resolved model and the upstream provider it routed to. Both are
    // absent on non-OpenRouter backends, so they stay nullable. Captured for usage accounting.
    val model: String? = null,
    val provider: String? = null,
)

data class Choice(
    val message: ChatMessage,
    @JsonProperty("finish_reason") val finishReason: String,
)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
    @JsonProperty("total_tokens") val totalTokens: Int? = null,
    // Prompt-caching breakdown. Present only when the upstream provider reports it (models with
    // explicit/implicit caching); absent on plain responses, so the whole object stays nullable.
    @JsonProperty("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
)

// Sub-breakdown of the prompt tokens, primarily for prompt caching. Every field is nullable
// because providers populate different subsets (and OpenRouter omits the object entirely when
// none apply). See OpenRouter usage-accounting / prompt-caching docs.
data class PromptTokensDetails(
    // Prompt tokens served from cache (cache hits).
    @JsonProperty("cached_tokens") val cachedTokens: Int? = null,
    // Prompt tokens written to cache — only returned for models with explicit cache-write pricing.
    @JsonProperty("cache_write_tokens") val cacheWriteTokens: Int? = null,
    // Audio input tokens, when the prompt carried audio.
    @JsonProperty("audio_tokens") val audioTokens: Int? = null,
)
