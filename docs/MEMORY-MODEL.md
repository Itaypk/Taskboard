# AI Assistant Memory Model

Design for what the weekly-planner AI "remembers" across turns and across sessions.
Status: implemented (the three stores below are all live). See `SPEC.md` for the broader product
context. The "soft extension" idea at the end is not built — tracked in `IDEAS.md`.

## Goals & non-goals

**Goals**
- Keep the assistant's behavior coherent across a single planning conversation.
- Carry forward enough between weeks that the user doesn't have to re-explain themselves.
- Stay simple enough to fit a hobby project — no vector store, no separate memory service.

**Non-goals (for v1)**
- LLM-curated long-term memory (the model silently writing facts about the user).
- Cross-user / shared memory.
- Learned behavioral patterns ("user always pushes back on Thursday meetings"). That belongs in
  the future Proactive Task Helper (see `IDEAS.md`), built as analytics over task signals — not as
  memory.

## The three stores

All memory lives in one of three places. Nothing else.

### 1. User context block (stable, user-authored)

Free-text facts the user maintains via the web UI settings page. Already called out in `SPEC.md`.

- One row per user, single `TEXT` column.
- Dropped into the system prompt verbatim on every planning turn.
- Examples: "I prefer deep work in the morning", "Tuesdays I leave early for pickup",
  "No work blocks after 8pm".
- The user is the sole author. The LLM never writes here directly (see "Soft extension" below
  for a proposed-edit flow that keeps the user authoritative).

**Why a free-text blob and not structured fields?** Hobby project, low volume, and the LLM
consumes it as prose anyway. Structure would be premature.

### 2. Planning session + last-session summary (recent, LLM-authored)

Each weekly planning conversation is a `PlanningSession` row. At the end of a session the LLM
writes a short summary that the *next* session prepends to its system prompt.

Sketch of the entity:

```
PlanningSession
  id: UUID
  user_id: UUID                  -- FK users(id)
  started_at: timestamp
  ended_at: timestamp?           -- null while active
  status: enum(ACTIVE, COMPLETED, ABANDONED)
  messages: jsonb                -- full transcript; conversations are short enough
  summary: text?                 -- written at session end, ~1 short paragraph
```

What goes into a summary:
- Tasks agreed to this week (and roughly when).
- Tasks discussed but deferred, with the reason if the user gave one.
- Anything the user mentioned that affects next week ("traveling Mon–Wed", "have a deadline
  shifting on the X project").

How it's used:
- The next session loads only the *previous* session's summary — not a chain of summaries.
  One hop back is enough at a weekly cadence; deeper history is reconstructible from task state.
- If the previous session was `ABANDONED` (user dropped off mid-conversation), prefer the
  one before it, or skip the summary entirely.

**Why store the full transcript too?** Debugging, and the option to re-summarize later if the
prompt format changes. Cheap at this scale.

### 3. Task-level signals (derived, schema-native)

Most of what feels like "memory about a task" is just fields on the task row. The planner reads
these directly when serializing the backlog into the prompt.

Fields that earn their keep:
- `reschedule_count: int` — incremented every time an agreed task slips to a later week.
- `last_scheduled_at: timestamp?` — when it was last placed on the calendar.
- `last_completed_at: timestamp?` — for recurring or repeated-pattern tasks (future).
- Existing `status`, `deadline`, `estimated_duration` already do most of the work.

With these the planner can naturally say "this task has been kicked four weeks running" without
any "memory" abstraction — it's just a column.

## How a planning turn assembles its prompt

Pseudocode for the system/context portion of each turn:

```
system_prompt = base_instructions
              + user_context_block(user)               // store 1
              + previous_session_summary(user)         // store 2, may be empty
              + serialize_backlog(user)                // store 3, lives in task fields
              + calendar_window_for_this_week(user)    // not memory; live read
messages       = current_session.messages              // full transcript so far
```

The current session's transcript is sent as-is — no truncation, no summarization mid-session.
The spec already commits to this ("conversations are short enough for this to be practical").

## Soft extension: assistant-proposed context edits (not built)

Idea for a v1.1: let the assistant *propose* additions to the user context block at the end of a
session rather than requiring the user to edit it by hand. See `IDEAS.md` for the sketch.

## What we are explicitly *not* building

- **A `memories` table** the LLM writes to via tool calls. Too easy to accumulate wrong/stale
  facts that the user never sees.
- **Embedding search over past conversations.** Conversations are weekly and short; the
  previous-summary hop covers what's needed.
- **Cross-session conversation threading.** Each weekly session is its own thing. The summary
  is the only thread.
- **Auto-extracted "user facts."** Same risk as the memories table, without even the LLM
  deciding when to write.

## Open questions to revisit when implementing

- Where does the summary get written from — a final tool call the LLM makes, or a separate
  post-session prompt? Leaning toward a separate post-session prompt so the planning prompt
  stays focused.
- Token budget for the user context block — soft cap in the UI (e.g. 2k chars) so it can't
  silently bloat the system prompt.
- Retention: do we ever delete old `PlanningSession` rows? Probably not for v1 — they're small
  and useful for debugging. Revisit if storage matters.
