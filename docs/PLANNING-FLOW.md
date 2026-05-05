# Weekly Planning Assistant — v1 Flow

This doc describes how a weekly planning conversation runs end-to-end. It complements
[`SPEC.md`](./SPEC.md) (product vision) and [`MEMORY-MODEL.md`](./MEMORY-MODEL.md)
(prompt-assembly and cross-session memory). Pre-session reconciliation (status sweep on
previously agreed tasks) and Telegram outbound are intentionally out of scope here.

## State machine

```
START
  └─► PlanningSessionService.startSession(userId)
  └─► Channel emits Choice("how's your week?", [light/normal/heavy/skip])
AWAITING_CAPACITY
  └─► On user reply (Selection or free text) → record capacity hint
ASSEMBLE_PROMPT
  └─► WeeklyPlanningPromptAssembler.assembleSystemPrompt(userId, capacity)
  └─► AiConversationManager.startConversation(config with submit_plan tool registered)
  └─► Send a kickoff user message ("Let's start. Capacity: ...") so the assistant gets
      to produce the first turn.
CONVERSING
  └─► Each user inbound → AiConversationManager.sendMessage → assistant text relayed via channel
  └─► Loop until the assistant calls submit_plan
SUBMITTED
  └─► SubmitPlanTool parses payload, drops it in PlanSubmissionInbox
  └─► Orchestrator drains inbox after the AI turn finishes, calls
      PlanningSessionService.completeSession(summary = plan.summary)
DONE
```

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

## Output contract: `submit_plan`

The assistant signals end-of-session by calling the `submit_plan` tool exactly once.
Schema (see `SubmitPlanTool.parameters`):

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
  "summary": "human-readable recap stored as the session summary"
}
```

The tool itself does no side-effects beyond recording into `PlanSubmissionInbox` (a
ThreadLocal-backed bucket). The orchestrator drains the inbox after `sendMessage` returns
and calls `PlanningSessionService.completeSession(summary = plan.summary)`. The structured
list of tasks lives in the conversation transcript (and is available for future pickup by
a calendar-writer); v1 deliberately doesn't add a new column for it.

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
3. Continue with `{"text":"..."}` until the assistant calls `submit_plan`. Phase flips
   to `DONE`; `PlanningSessionEntity.summary` is populated.
4. `GET /api/dev/planning/{sessionId}` — returns current phase plus any pending messages
   not yet drained.
5. `POST /api/dev/planning/{sessionId}/abandon` — cancels the session.

The `tasker.ai.api-key` env var must be set; the model is configurable via
`tasker.ai.weekly-planning-model` (default `anthropic/claude-sonnet-4.5`, routed through
OpenRouter as configured in `application.yaml`).

## Out of scope for v1 (with hooks left in place)

- **Pre-session reconciliation**: status sweep over previously agreed tasks (mark
  done/not done with inline buttons) before the assistant kicks in.
- **Real calendar reads**: `StubCalendarWindowProvider` returns a placeholder string;
  swap in a Google Calendar implementation behind the same interface.
- **LLM-driven inline buttons mid-conversation**: the channel models support `Choice`
  outbound, but only the capacity pre-prompt uses it today. Slot-picker flows would need
  the AI tool loop to support "interactive" tools that pause for user input.
- **Post-session summarizer prompt**: today the `submit_plan.summary` is what we store.
  A separate, focused summarization prompt could replace it later.
- **Persisted orchestrator state**: in-memory; rebuild across pod restart will require
  pulling the last session/conversation back from the DB.
