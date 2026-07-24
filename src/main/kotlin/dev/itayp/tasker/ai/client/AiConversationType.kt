package dev.itayp.tasker.ai.client

/**
 * Known conversation types used to tag AI usage. The interactive weekly-planning conversation
 * stores its type on the conversation entity (see `WeeklyPlanningOrchestrator.CONVERSATION_TYPE`);
 * the single-shot sub-agents have no stored conversation, so they pass their type here directly.
 *
 * App-domain tags — the extracted `openrouter-client` library treats `conversationType` as an
 * opaque string, so these constants stay in the app (the one class kept in this package after the
 * client core moved to the library).
 */
object AiConversationType {
    const val WEEKLY_PLANNING = "weekly_planning"
    const val TASK_SEARCH = "task_search"
    const val TASK_SUGGESTION = "task_suggestion"
    const val SLOT_REMINDER = "slot_reminder"
}
