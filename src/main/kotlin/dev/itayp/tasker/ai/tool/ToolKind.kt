package dev.itayp.tasker.ai.tool

/**
 * Determines how the orchestrator dispatches a tool call and whether the model is
 * re-invoked after the call completes. See `docs/PLANNING-FLOW.md` for the full
 * conversation contract.
 */
enum class ToolKind {
    /**
     * The model speaks to the user via this tool (e.g. `say`) or signals an
     * end-of-session side effect (`submit_plan`). The orchestrator handles any
     * channel rendering / persistence; the tool's `execute` records a short ack as
     * the `tool_result`. The model is NOT re-invoked solely because of one-way calls.
     */
    ONE_WAY_OUTPUT,

    /**
     * The model is asking the user a question (e.g. `ask_choice`). The orchestrator
     * queues the question, dispatches it through the channel, and fills in the
     * `tool_result` once the user replies. Multiple interactive calls in one turn
     * are answered serially without re-invoking the model in between. When the queue
     * drains (or the user picks an escape option), the model is re-invoked exactly
     * once with all collected `tool_result`s in the transcript.
     */
    INTERACTIVE_INPUT,

    /**
     * Synchronous backend call (e.g. a future `lookup_calendar`). `execute` runs
     * immediately and produces a real result string; the model is re-invoked so it
     * can reason about the data.
     */
    DATA_LOOKUP,
}
