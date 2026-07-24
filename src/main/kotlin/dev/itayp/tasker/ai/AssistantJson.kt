package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.parseAssistantJsonResponse
import dev.itayp.nescioquid.openrouter.redactLlmResponse
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import tools.jackson.databind.ObjectMapper

/**
 * The metered, app-side wrapper over the library's [parseAssistantJsonResponse]: catches the
 * failure itself, increments `tasker.ai.parse_failures` (tagged by [conversationType] and a coarse
 * `reason` — `no_json_found` when no `{ ... }` span was present at all, `deserialize_error` when a
 * span was found but didn't match [type]) and logs a warning, then returns null so the caller can
 * fall back.
 *
 * Never logs the raw response: for these sub-agents it typically echoes back user-authored text (a
 * task title/description drafted from the user's own request), which CLAUDE.md forbids in logs at
 * any level. [redactLlmResponse] instead logs the response's shape — keys, nesting, array/object
 * structure — with every leaf value redacted, which is what actually explains a schema mismatch.
 *
 * Stays in the app (not the library) because it carries the app's Micrometer metric; the library
 * keeps only the pure `parseAssistantJsonResponse` / `extractJsonObjectSpan` / `redactLlmResponse`.
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
