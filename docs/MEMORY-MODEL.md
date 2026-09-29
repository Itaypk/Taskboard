# AI Assistant Memory Model

Design for what the weekly-planner AI "remembers" across turns and across sessions.
Status: implemented (the three stores below are all live, and the "soft extension" — assistant-proposed
context edits — is now built). See `SPEC.md` for the broader product context.

## Goals & non-goals

**Goals**
- Keep the assistant's behavior coherent across a single planning conversation.
- Carry forward enough between weeks that the user doesn't have to re-explain themselves.
- Stay simple enough to fit a hobby project — no vector store, no separate memory service.

**Non-goals (for v1)**
- LLM-curated long-term memory (the model silently writing facts about the user).
- Cross-user / shared memory.
- Learned behavioral patterns ("user always pushes back on Thursday meetings"). That belongs in
  the future Proactive Task Helper (issue #232), built as analytics over task signals — not as
  memory.

## The three stores

All memory lives in one of three places. Nothing else.

### 1. User context block (stable, user-authored)

Free-text facts the user maintains via the web UI settings page. Already called out in `SPEC.md`.

- One row per user, single `TEXT` column.
- Dropped into the system prompt verbatim on every planning turn.
- Examples: "I prefer deep work in the morning", "Tuesdays I leave early for pickup",
  "No work blocks after 8pm".
- The user is the sole author. The LLM never writes here directly — but at the end of a planning
  session it may *propose* one durable line to append, which the user accepts or rejects inline
  (see "Soft extension" below). Only an accepted proposal is written, so the user stays authoritative.

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

### 4. The inter-session change feed

`backlog_task_change_event` is an append-only log of creations, status transitions, and deletions,
with an encrypted title snapshot so an entry survives its task row. It backs the "What changed since
the last session" block.

Because it is *history*, it deliberately doesn't know today's visibility rules — a snapshot exists for
tasks that are now archived, future-dated, hidden from the assistant, or owned by another board member.
So the split at render time (`WeeklyPlanningPromptAssembler.renderDiff`) is load-bearing:

- **Past-tense buckets** (completed / removed / deleted) are rendered verbatim. Saying "you finished X"
  must keep working after X is gone.
- **Forward-looking buckets** (newly added / reopened) name tasks the model is invited to *propose*, so
  they're intersected with `PlannerTaskSelector.visibleTaskIds` — the same per-task rules the candidate
  slate applies. Skipping this makes the assistant suggest work that isn't in the backlog.

The feed as a whole is also scoped to AI-allowed boards (`AiAccessService.aiAllowedBoardIds`), like
every other prompt input.

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

## Soft extension: assistant-proposed context edits (built)

The assistant can *propose* one addition to the user context block at the end of a session rather than
requiring the user to edit it by hand. Implementation:

- The `submit_plan` tool carries an optional `context_suggestion` — a single durable, first-person
  context line the model learned this session (prompt guidance: only when it's genuinely new, stable,
  and not already in the block; otherwise omit).
- After the plan is committed, the orchestrator asks a deterministic accept/reject question
  (`AWAITING_CONTEXT_PROPOSAL_REPLY`, an orchestrator-owned Choice — not an LLM turn), which renders
  natively on both web and Telegram. See `PLANNING-FLOW.md`.
- Accepting appends the line to the block via `UserSettingsService.appendToContextBlock` (respecting
  the ~4k-char cap); rejecting is a no-op. Nothing is persisted about a proposal that isn't accepted —
  the question lives only in the in-memory session, so dropping off simply forgoes the addition.

The user remains the sole author: nothing is written without an explicit accept, and the appended line
is ordinary block text they can later edit or delete in Settings.

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
