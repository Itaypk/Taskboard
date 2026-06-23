package dev.itayp.tasker.ai.client

import java.util.UUID

/**
 * Caller-supplied context attached to every [AiClient.chat] call so the usage tracker can
 * attribute the call to a user, a conversation type, and — when the call belongs to a stored
 * multi-turn conversation — the conversation (and, where the caller has it, the planning session).
 *
 * This is the unit of AI usage accounting: one [chat] call ⇒ one usage record.
 */
data class AiCallContext(
    val userId: UUID,
    val conversationType: String,
    /** The planning session this call belongs to, when the caller has it directly. */
    val sessionId: UUID? = null,
    /** The stored AI conversation this call belongs to (multi-turn flows only). */
    val conversationId: UUID? = null,
)

/**
 * Known conversation types used to tag AI usage. The interactive weekly-planning conversation
 * stores its type on the conversation entity (see `WeeklyPlanningOrchestrator.CONVERSATION_TYPE`);
 * the single-shot sub-agents have no stored conversation, so they pass their type here directly.
 */
object AiConversationType {
    const val WEEKLY_PLANNING = "weekly_planning"
    const val TASK_SEARCH = "task_search"
    const val TASK_SUGGESTION = "task_suggestion"
    const val SLOT_REMINDER = "slot_reminder"
}
