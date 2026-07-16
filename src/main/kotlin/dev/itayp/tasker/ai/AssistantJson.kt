package dev.itayp.tasker.ai

import dev.itayp.tasker.util.redactLlmResponse
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import tools.jackson.databind.ObjectMapper

/**
 * Parses a single JSON object out of an LLM response, tolerating surrounding prose or Markdown code
 * fences (```json ... ```) around it. Returns the deserialized [type].
 *
 * Throws if no `{ ... }` span is present or the span fails to deserialize, so callers can log the
 * details or propagate; they decide how to recover (e.g. fall back to an empty result).
 *
 * Shared by the planner's sub-agents (task search, task suggestion), which ask the model for a JSON
 * object but can't fully rely on it omitting fences/prose.
 */
fun <T : Any> parseAssistantJsonResponse(objectMapper: ObjectMapper, raw: String, type: Class<T>): T {
    return objectMapper.readValue(extractJsonObjectSpan(raw), type)
}

/**
 * [parseAssistantJsonResponse], but catches the failure itself: increments `tasker.ai.parse_failures`
 * (tagged by [conversationType] and a coarse `reason` — `no_json_found` when no `{ ... }` span was
 * present at all, `deserialize_error` when a span was found but didn't match [type]) and logs a
 * warning, then returns null so the caller can fall back.
 *
 * Never logs the raw response: for these sub-agents it typically echoes back user-authored text (a
 * task title/description drafted from the user's own request), which CLAUDE.md forbids in logs at
 * any level. [redactLlmResponse] instead logs the response's shape — keys, nesting, array/object
 * structure — with every leaf value redacted, which is what actually explains a schema mismatch.
 */
fun <T : Any> parseAssistantJsonResponseOrNull(
    objectMapper: ObjectMapper,
    raw: String,
    type: Class<T>,
    conversationType: String,
    meterRegistry: MeterRegistry,
    log: Logger,
    agentLabel: String,
): T? = runCatching { parseAssistantJsonResponse(objectMapper, raw, type) }
    .onFailure { e ->
        val reason = if (e is IllegalArgumentException) "no_json_found" else "deserialize_error"
        meterRegistry.counter(
            "tasker.ai.parse_failures", "conversation_type", conversationType, "reason", reason,
        ).increment()
        log.warn("{} could not parse sub-agent output ({}): {}", agentLabel, reason, e.message)
        log.debug("{} redacted response shape: {}", agentLabel, redactLlmResponse(raw))
    }
    .getOrNull()

/**
 * Returns the `{ ... }` span from an LLM response, tolerating surrounding prose or code fences.
 * Throws if no balanced-looking object span is present. Useful when the caller wants to inspect
 * the parsed tree before binding it to a type (e.g. branching on which of two shapes came back).
 */
fun extractJsonObjectSpan(raw: String): String {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start in 0 until end) { "No JSON object found in assistant response" }
    return raw.substring(start, end + 1)
}
