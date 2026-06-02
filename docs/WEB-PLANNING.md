# Web-based weekly planning

## Why

Weekly planning sessions historically only ran over Telegram. This adds a
**web-based planning conversation** so users can plan from the browser (desktop
and mobile) without a linked Telegram account.

The planning core is already channel-agnostic: `WeeklyPlanningOrchestrator` only
talks to the `ConversationChannel` interface and runs the entire model/tool loop
*synchronously* inside each `handleInbound` call. The web channel is therefore an
extension of an existing seam — a production controller, a buffered channel with
a Markdown formatter, and a React drawer — not a rewrite. The Telegram channel is
unchanged.

## Architecture

```
Browser (PlanningDrawer.tsx)
   │  POST /api/v1/planning/{start,reply,revise,abandon}   (session auth + CSRF)
   ▼
WebPlanningController
   │  builds a fresh BufferedConversationChannel(MarkdownMessageFormatter) per request
   ▼
WeeklyPlanningOrchestrator   ◄── same instance the Telegram channel drives
   │  runs one synchronous turn, calling channel.send() inline
   ▼
channel.drain() → PlanningTurnResponse { sessionId, phase, messages[] }
```

Because the orchestrator drains the whole turn before returning and holds all
cross-request state in its own in-memory `ConcurrentHashMap<sessionId, …>`, the
controller is effectively stateless: it creates a buffered channel per request,
drives the orchestrator, and returns the buffered messages. The browser holds the
returned `sessionId` and passes it back on the next turn (there is no chat-id
registry like Telegram's `TelegramSessionRegistry`).

### Delivery model

**Synchronous request/response.** Each user action POSTs and the request blocks
while the orchestrator runs the full model turn (possibly several tool round-trips),
then returns every buffered message at once. The drawer shows a "thinking…"
indicator while the request is in flight. No SSE/WebSocket streaming in v1.

### Entry decision

`GET /api/v1/planning/entry` returns the data the drawer needs to decide what to
offer, mirroring the three cases of the Telegram `/plan` command but as flags the
React UI renders into explicit buttons:

| Field | Meaning |
|-------|---------|
| `activeSessionId` | An ACTIVE session whose in-memory orchestrator state is still live → offer Continue / Abandon. Gated on `orchestrator.phase(id) != null` so a session lost to a restart is not falsely offered. |
| `completedPlanSummary` + `revisableSessionId` | The latest COMPLETED plan → offer Revise / Start over. |
| `thisWeek` / `nextWeek` | `{ weekStart, weekEnd }` ISO dates from `WeekResolver`, using the user's timezone + `weekStartDay`. |

The capacity prompt and all conversation copy stay server-rendered and localized
via the orchestrator + `MessageSource`.

### Endpoints (`/api/v1/planning`, session auth + CSRF, not profile-gated)

- `GET  /entry` → `PlanningEntryResponse`
- `GET  /{sessionId}` → `PlanningTranscriptResponse` (reconstructed transcript; 409 if no in-memory state, 404 if not owned)
- `POST /start` `{ offset: "CURRENT" | "NEXT" }` → `PlanningTurnResponse`
- `POST /{sessionId}/reply` `{ text?, optionId? }` → `PlanningTurnResponse` (409 if no in-memory state)
- `POST /{sessionId}/revise` → `PlanningTurnResponse` (404 not found / 409 not COMPLETED)
- `POST /{sessionId}/abandon` → 204

Ownership is enforced via `PlanningSessionService.findById(userId, sessionId)`
before reply, so a guessed UUID cannot drive another user's session.

### Cancelling a session / rollback

The drawer shows a **"Leave session"** control (confirmation dialog) during an
in-progress chat; it `POST`s `/abandon` and returns to the entry screen.

Cancelling is a clean rollback because **starting a session has no effect on the
existing plan**:

- `findCurrentPlan` returns the most recently **finalized** (completed) plan and
  never an in-progress ACTIVE session. Planned tasks are only written at submit,
  so starting a session (e.g. for next week) no longer hides the current plan.
- The carry-over reschedule-count bump runs at **finalize** time
  (`PlanFinalizationService.complete` → `bumpRescheduleCountsForCarriedOverTasks`),
  not at session start — so an abandoned session leaves the existing plan's task
  stats untouched.

Abandoning therefore restores nothing because nothing was overwritten; it simply
drops the ACTIVE session. Backlog edits the assistant made mid-session via
`create_task` / `update_task` are real, immediate edits and are **not** rolled
back (the user explicitly asked for them).

### Transcript resume (phase 2a)

Reloading the browser loses the React-only transcript but not the session: the
`GET /{sessionId}` endpoint rebuilds the conversation from stored messages via
`PlanningTranscriptService`, and the drawer's "Continue planning" entry option
loads it. Reconstruction mirrors how the live channel rendered each message:

- assistant `say` → text (+ suggested replies); `ask_choice` → choice;
  `submit_plan` → the closing message (suppressed if a `say` spoke in the same turn).
- the user's `ask_choice` tool-result → a user bubble; free-text user messages → user bubbles.
- the position-0 kickoff, data-lookup tools (`find_task`/`create_task`/…), and one-way
  acks are internal and skipped. The capacity Q&A aren't in the conversation, so a
  reconstructed transcript starts at the first real assistant message (a session still
  `AWAITING_CAPACITY` re-renders the capacity question instead).

This works while the orchestrator still holds the session in memory. After a
server restart that state is gone, so `GET /{sessionId}` returns **409** and the
drawer falls back to the entry screen — persisting/rehydrating the orchestrator
state is phase 2b.

### Formatting

`MarkdownMessageFormatter` (in `channel/MessageFormatter.kt`) maps the supported
features onto Markdown (`**bold**`, `*italic*`, `-` bullets) so the model's output
renders through the frontend `MarkdownRenderer` (marked + DOMPurify). The dev
console keeps `PlainTextMessageFormatter`. The buffered channel
(`BufferedConversationChannel`, formerly `InMemoryConversationChannel`) is shared
by both the dev console and the web channel; the formatter is a constructor
parameter.

## Known limitations (v1)

- **No resume across a server restart.** Transcript resume (phase 2a) works while
  the orchestrator holds the session in memory, but its phase/pending-queue state
  is not persisted, so a restart mid-session drops it: `GET`/`reply` return **409**
  and the drawer resets to the entry screen. This matches existing Telegram
  behavior. Acceptable for a single prod instance where short downtime is fine.
  See Phase 2b.
- **No per-session locking.** A user driving the same session from both Telegram
  and the web simultaneously could interleave model turns. Low practical risk;
  see Phase 2c.

## Phases

- **Phase 1 (done):** end-to-end synchronous web planning MVP — buffered
  channel + Markdown formatter, `WebPlanningController`, dedicated `PlanningDrawer`.
- **Phase 2a (done):** reconstruct the transcript from stored conversation messages
  (`PlanningTranscriptService` + `GET /{sessionId}`) so a page reload resumes the chat
  while the session is still in memory.
- **Phase 2b (deferred):** persist orchestrator phase + pending-interactive queue and
  rehydrate after a server restart (also makes `GET`/`reply` survive a restart).
- **Phase 2c (deferred):** per-`sessionId` locking for concurrent Telegram + web.
- **Phase 3 (polish):** richer typing animation, localized entry-screen strings if
  non-English users need them, mobile bottom-sheet keyboard tuning.

## Key files

- `controller/WebPlanningController.kt`, `controller/PlanningResponses.kt`
- `planning/PlanningTranscriptService.kt` (transcript reconstruction, phase 2a)
- `channel/BufferedConversationChannel.kt`, `channel/MessageFormatter.kt` (`MarkdownMessageFormatter`)
- `tasker-frontend/src/components/PlanningDrawer.tsx` + `.module.css`
- `tasker-frontend/src/api.ts` (planning client), `tasker-frontend/src/App.tsx` (header button + drawer)
- Reused unchanged: `WeeklyPlanningOrchestrator`, `PlanningSessionService`, `WeekResolver`.
