package dev.itayp.tasker.ai

import tools.jackson.databind.ObjectMapper

/**
 * Parses a single JSON object out of an LLM response, tolerating surrounding prose or Markdown code
 * fences (```json ... ```). Returns the deserialized [type], or null if no parseable object is found.
 *
 * Shared by the planner's sub-agents (task search, task suggestion), which ask the model for a JSON
 * object but can't fully rely on it omitting fences/prose.
 */
fun <T : Any> safeParseAssistantJsonResponse(objectMapper: ObjectMapper, raw: String, type: Class<T>): T? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = raw.substring(start, end + 1)
    return runCatching { objectMapper.readValue(json, type) }.getOrNull()
}
