# Weekly Planning Assistant — v1 Flow

This doc describes how a weekly planning conversation runs end-to-end. It complements
[`SPEC.md`](./SPEC.md) (product vision) and [`MEMORY-MODEL.md`](./MEMORY-MODEL.md)
(prompt-assembly and cross-session memory). Telegram outbound is intentionally out of scope here.

## State machine

```
START
  └─► PlanningSessionService.startSession(userId)
  └─► Channel emits Choice("how's your week?", [light/normal/heavy/skip])
AWAITING_CAPACITY
  └─► On user reply (Selection or free text) → record capacity hint
RECONCILE (only if last week's plan has unfinished, past-due tasks)
  └─► PlanningReconciliationService.findUnfinishedTasks(userId, weekStart): tasks from the
      most recent completed plan whose latest slot has passed and are still TODO.
  └─► AWAITING_RECONCILE_REPLY: a deterministic Choice per task (no LLM) —
      [mark done / carry over / return to queue / archive], served one at a time.
        • done    → BacklogTaskService.markDone
        • archive → BacklogTaskService.archive
        • carry   → collected and injected into the assistant's context
        • queue   → left as-is in the backlog
  └─► When the queue drains, proceed to ASSEMBLE_PROMPT with the carried-over tasks.
ASSEMBLE_PROMPT
  └─► WeeklyPlanningPromptAssembler.assembleSystemPrompt(userId, capacity, …, carriedOver)
  └─► AiConversationManager.startConversation(config with the planning toolset
      registered: say, ask_choice, submit_plan)
  └─► Send a kickoff user message ("Let's start. Capacity: ...") so the assistant gets
      to produce the first turn.
CONVERSING
  └─► Each user inbound is consumed by the orchestrator.
      ┌─ If the conversation is suspended waiting on `ask_choice` answers, the inbound
      │  fills the next pending tool_call_id and either continues the queue or, when
      │  drained, resumes the model with the collected tool_results.
      └─ Otherwise, inbound is forwarded to AiConversationManager.sendMessage; the
         resulting tool calls are processed (see "Tool kinds" below).
  └─► Loop until the assistant calls submit_plan
SUBMITTED
  └─► SubmitPlanTool parses payload, drops it in PlanSubmissionInbox
  └─► Orchestrator drains inbox after the AI turn finishes, calls
      PlanningSessionService.completeSession(summary = plan.summary)
  └─► If the plan carried a `context_suggestion`, the orchestrator renders the closing
      message and then AWAITING_CONTEXT_PROPOSAL_REPLY (see below); otherwise → DONE.
AWAITING_CONTEXT_PROPOSAL_REPLY (only if submit_plan included a context_suggestion)
  └─► A deterministic accept/reject Choice (no LLM) asking whether to add the suggested
      fact to the user's context block. Owned by the orchestrator like the capacity and
      reconcile prompts — it is NOT part of the AI transcript.
        • accept  → UserSettingsService.appendToContextBlock (respects the 4k cap)
        • decline / free text → no-op
  └─► Sends a short localized ack, then → DONE. The plan is already committed at SUBMITTED,
      so dropping off here (closing the drawer, ignoring the Telegram buttons) only forgoes
      the context addition; the plan stands.
DONE
```

## Conversation contract: every assistant turn speaks via tools

The model never emits free-text content for the user to read. Instead it calls one or
more of the planning tools per turn; the orchestrator translates calls into channel
messages (or persisted side-effects) and decides whether the model needs to be re-invoked
based on the **kind** of each tool.

### Tool kinds

| Kind                | Examples                  | Executes when                                                      | Result fed to model? | Ends turn?                                  |
|---------------------|---------------------------|--------------------------------------------------------------------|----------------------|---------------------------------------------|
| One-way output      | `say`, `submit_plan`      | Immediately, in the order returned                                  | No (noop ack)        | Yes, unless other kinds also fired          |
| Interactive input   | `ask_choice`              | Deferred — queued; rendered to channel one at a time as user answers | Yes, as `tool_result` | Yes — turn suspends until the queue drains |
| Data lookup (future)| e.g. `lookup_calendar`    | Immediately (synchronous backend call)                              | Yes, as `tool_result` | No — model is re-invoked with the result    |

Re-entry rule for `AiConversationManager`: after processing one turn's tool calls,
re-invoke the model **iff** at least one data-lookup tool ran. If only one-way and/or
interactive tools fired, return control to the orchestrator. Interactive tools cause an
explicit "suspended" return so the orchestrator knows it owns the next several inbounds.

### The planning toolset

#### `say(text, suggested_replies?: string[])` — one-way

Renders as `ChannelMessage.Text`. `suggested_replies` populates `completions` if the
channel supports autocompletions; otherwise it's dropped. The model can call `say`
multiple times in a single turn; messages render in tool_call order.

#### `ask_choice(prompt, options: [{id, label}])` — interactive

Renders as `ChannelMessage.Choice`. Each call is queued as a pending tool result keyed
by its `tool_call_id`. The orchestrator dispatches them serially: emit Q1, wait for the
user's selection, fill the result for Q1's `tool_call_id`, emit Q2, etc. When the queue
drains (or the user picks an escape option, see below), all collected
`{role: "tool", tool_call_id, content: "{\"choice_id\":..., \"label\":..., \"free_text\":...}"}`
messages are appended and the model is re-invoked once.

Recommended convention in the prompt: emit at most one `ask_choice` per turn unless the
follow-ups are independent (e.g. asking about Task A's slot, then Task B's slot, etc.).
The orchestrator does not enforce this; ordering of the array is preserved.

#### `submit_plan(tasks, summary, message, context_suggestion?)` — one-way, terminal

```json
{
  "tasks": [
    {
      "task_id": "uuid (optional, omit for ad-hoc)",
      "title": "string",
      "slots": [{ "start_iso": "...", "end_iso": "...", "label": "..." }],
      "notes": "string (optional)"
    }
  ],
  "summary": "human-readable recap stored as the session summary",
  "message": "user-facing closing farewell",
  "context_suggestion": "optional durable fact to propose adding to the user context block"
}
```

Drops the parsed payload into `PlanSubmissionInbox`. After the turn completes, the
orchestrator calls `PlanningSessionService.completeSession(summary = plan.summary)`. If the
model supplied a `context_suggestion`, the orchestrator asks the user to accept/reject it
(the `AWAITING_CONTEXT_PROPOSAL_REPLY` step above) before reaching `DONE`; otherwise it goes
straight to `DONE`. The structured task list lives in the conversation transcript; v1 does
not add a new column for it.

A typical farewell turn looks like `submit_plan(...)` + `say("Have a great week!")` in
the same `tool_calls` array — both run, no re-entry, session ends.

### Suspension and the escape hatch

The interactive queue is the only mechanism that crosses multiple user inbounds without
a model call in between. While suspended:

- The orchestrator records its phase as `AWAITING_INTERACTIVE_REPLY` and tracks the
  pending tool_call_ids in order.
- Each inbound (text or selection) is bound to the next pending `tool_call_id`. Free
  text becomes the `free_text` field; a selection populates `choice_id` and `label`.
- Every `ask_choice` SHOULD include an escape option (recommended id `discuss`, label
  "Let's talk about it"). When the user picks it, the orchestrator drops the rest of
  the queue, marks the remaining tool_call_ids with a `{"skipped": true}` result, and
  re-invokes the model immediately. The model sees both the answers it got and the
  ones the user opted out of, and can adjust.

### Ordering rules (mixed turns)

If a single turn contains, in order: `say(A)`, `ask_choice(Q1)`, `say(B)`,
`ask_choice(Q2)`:

1. `say(A)` renders.
2. `ask_choice(Q1)` is queued; turn is now interactive.
3. `say(B)` renders right away (still part of this turn's output).
4. `ask_choice(Q2)` is queued.
5. Orchestrator dispatches Q1, waits for the answer, then Q2, waits for the answer.
6. Both tool_results posted; model re-invoked once.

In short: one-way calls always render in declared order; interactive calls render
serially in declared order, gated on user input.

## Prompt assembly

Order mirrors `MEMORY-MODEL.md`:

```
system_prompt = base instructions (tone, output contract)
              + user context block        (UserSettings.contextBlock)
              + previous session summary  (PlanningSessionService.findPreviousSummarizableSession)
              + task change diff          (PlanningSessionService.diffSincePreviousSession)
              + backlog candidates        (PlannerTaskSelector → urgent + stale)
              + calendar window           (CalendarWindowProvider; stub returns "not connected")
              + capacity hint             (collected via the pre-prompt)
              + environment               (today, tz, language)
```

Templates live under `src/main/resources/prompts/weekly-planning/`:
- `system.md` — main system prompt.
- `capacity-question.md` — the pre-prompt text shown alongside the Choice options.
- `kickoff-message.md` — the synthetic user turn that primes the assistant.

`PromptTemplate` does `{{var}}` substitution and throws on missing/unknown variables so
template/code drift surfaces loudly.

## Channel abstraction

`ConversationChannel` exposes `capabilities` (autocompletions, inline buttons) plus a
single `send(ChannelMessage)` method. Outbound messages are either:
- `ChannelMessage.Text(text, completions = [...])`
- `ChannelMessage.Choice(prompt, options = [...])`

Inbound is `ChannelInbound.Text(text)` or `ChannelInbound.Selection(optionId, freeText?)`.
Adapters degrade gracefully — e.g. a channel without inline buttons can render `Choice`
as a numbered list.

`InMemoryConversationChannel` is the dev-mode adapter: outbound messages are buffered and
drained by the controller on each round trip.

## Manual testing (no Telegram needed)

Profile: `dev`. Endpoints under `/api/dev/planning/*`. Authenticate via the existing
`/api/auth/dev-login`.

1. `POST /api/dev/planning/start` — opens a session; response includes the capacity
   `Choice` message.
2. `POST /api/dev/planning/{sessionId}/reply` with `{"optionId":"normal"}` — orchestrator
   advances to `CONVERSING`, the assistant produces its first turn (returned as a `Text`
   message in the response).
3. Continue with `{"text":"..."}` or `{"optionId":"..."}` until the assistant calls
   `submit_plan`. While the assistant is in an interactive queue (one or more
   `ask_choice` calls outstanding), each inbound fills the next pending question's
   tool_result without invoking the model — the orchestrator only re-enters the model
   once the queue drains or the user picks an escape option. Phase flips to `DONE`;
   `PlanningSessionEntity.summary` is populated.
4. `GET /api/dev/planning/{sessionId}` — returns current phase plus any pending messages
   not yet drained.
5. `POST /api/dev/planning/{sessionId}/abandon` — cancels the session.

The `tasker.ai.api-key` env var must be set; the model is configurable via
`tasker.ai.weekly-planning-model` (default `anthropic/claude-sonnet-4.5`, routed through
OpenRouter as configured in `application.yaml`).

## Out of scope for v1 (with hooks left in place)

- **Real (Google) calendar reads**: `OneOffEventCalendarWindowProvider` surfaces one-off
  events captured through `/add` with a "partial" caveat in the prompt; a Google Calendar
  implementation could replace or extend it behind the same `CalendarWindowProvider` interface.
- **Post-session summarizer prompt**: today the `submit_plan.summary` is what we store.
  A separate, focused summarization prompt could replace it later.
- **Persisted orchestrator state**: in-memory; rebuild across pod restart will require
  pulling the last session/conversation back from the DB.
