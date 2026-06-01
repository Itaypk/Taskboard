package dev.itayp.tasker.ai

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
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start in 0 until end) { "No JSON object found in assistant response" }
    return objectMapper.readValue(raw.substring(start, end + 1), type)
}
