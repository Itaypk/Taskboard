package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One item proposed by the quick-add sub-agent. The /add flow may capture a task, a one-off
 * calendar event, or a mix of both from a single free-text request (e.g. "parent-teacher
 * conference Wed 7pm + prepare questions beforehand" → one event + one task).
 */
sealed interface CapturedItem {
    data class Task(val draft: TaskDraft) : CapturedItem
    data class Event(val draft: EventDraft) : CapturedItem
}

/**
 * AI-emitted event shape. Times are ISO-8601 strings with offset (e.g. `2026-07-15T19:30:00+03:00`)
 * because the model returns whatever the user implied in their local terms and we resolve to an
 * Instant at validation time using the user's configured timezone as a fallback when no offset is
 * supplied. [endIso] is optional — when omitted or earlier than [startIso], the flow defaults the
 * end to start + 60 minutes.
 */
data class EventDraft(
    val title: String,
    @JsonProperty("start") val startIso: String,
    @JsonProperty("end") val endIso: String? = null,
    val location: String? = null,
    val notes: String? = null,
)
