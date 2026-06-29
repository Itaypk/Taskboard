# `/add` — one-off calendar events

Extends the existing `/add` quick-capture flow with a second possible outcome:
instead of (or in addition to) a `TaskDraft`, the AI may propose one or more
**one-off calendar events**. After the user confirms the card, each event is
materialised as a calendar invitation email — reusing the same iCal pipeline
the weekly planner already uses to write time blocks. Nothing about the
existing task path changes.

## Motivation

Today `/add` only knows about tasks. A user pasting a parent-teacher notice,
a flight confirmation, or a forwarded meeting time gets either a low-value
task (title only, deadline maybe) or has to give up and copy it into their
calendar by hand. This adds the missing primitive without building a new
surface or a new storage substrate.

Out of scope (kept deliberately small):

- No recurrence, no attendees, no RSVPs — exactly one user, exactly one event.
- No editable internal "events" table beyond what we need to render them in
  the planner drawer (see "Editing", below).
- No new channel surface — Telegram `/add` is the only entry point in v1.
- No Google Calendar API integration. Delivery is the existing
  `CalendarInvitationComposer` email pipeline.

## User-facing flow

1. `/add Parent-teacher conference, Wed 19:30 at school auditorium`
2. Agent classifies as event (or task, or mixed). One AI call, same shape as
   today — no tool loop.
3. Card renders. For an event item: title, start, end, location, notes.
   Save / Adjust / Cancel — same buttons, same state machine.
4. On Save: one invite email per event, sent to the user's verified address
   from the `scheduling` SMTP sender. The user accepts/declines in their
   own calendar client; that client owns the editable copy.
5. Saved events also persist as lightweight rows so the weekly plan drawer
   can list them (read-only, with a "partial list" disclaimer).

Mixed outcome ("prepare for the conference" + "the conference itself") is
allowed. The card shows both items; Save commits both atomically.

## Engineering plan

### 1. Widen the AI outcome (single-shot, no tool loop)

`TaskSuggestionAgent.runQuickAdd` already returns a `SuggestionOutcome`
(`Draft | Clarify | Unparseable`). Today `Draft` carries one `TaskDraft`.
Replace with a list of typed items:

```kotlin
sealed interface CapturedItem {
    data class Task(val draft: TaskDraft) : CapturedItem
    data class Event(val draft: EventDraft) : CapturedItem
}

data class SuggestionOutcome.Draft(val items: List<CapturedItem>) : ...
```

`EventDraft` (new):

```kotlin
data class EventDraft(
    val title: String,
    val startIso: String,   // ISO-8601 with offset, in user's tz
    val endIso: String,     // ditto; default start + 60 min if model omits
    val location: String?,
    val notes: String?,
)
```

Prompt changes (`task-suggestion/system-clarify.md`,
`quickadd-user.md`): replace the single-task draft shape with an `items`
array where each entry is `{kind: "task", ...}` or `{kind: "event", ...}`.
A single-task draft becomes a one-item array. No backwards-compat shorthand
— the test fixtures get updated; the simpler code and tighter prompt are
worth the churn. The clarify branch is untouched.

Validation (`QuickAddFlow.validate`): for event items, parse ISO timestamps,
reject ones in the past beyond some grace (~1 hour), default `end` to
`start + 60min` when missing or `<= start`. Drop the item if the start is
unparseable rather than failing the whole batch.

### 2. Storage

New table `one_off_event` (new Liquibase changeset, next integer id):

| column          | type                 | notes                                  |
| --------------- | -------------------- | -------------------------------------- |
| id              | UUID PK              |                                        |
| user_id         | UUID FK users(id)    |                                        |
| board_id        | UUID FK boards(id)   | resolved default board, same as tasks  |
| title           | LONGVARCHAR          | encrypted under user DEK               |
| starts_at       | TIMESTAMPTZ          |                                        |
| ends_at         | TIMESTAMPTZ          |                                        |
| location        | LONGVARCHAR NULL     | encrypted under user DEK               |
| notes           | LONGVARCHAR NULL     | encrypted under user DEK               |
| ical_uid        | VARCHAR(128)         | stable UID emitted in the iCal invite  |
| created_at      | TIMESTAMPTZ          |                                        |
| cancelled_at    | TIMESTAMPTZ NULL     | for future "cancel" flow               |

Title, location, and notes are encrypted under the user's DEK — same
sensitivity tier as task titles, which already use this convention.

JPA entity + Spring Data repository, mirroring `BacklogTaskEntity` /
`BacklogTaskRepository`. No update endpoint in v1 (see "Editing").

### 3. Save path

New `OneOffEventService` (alongside `BacklogTaskService`):

- `createEvents(userId, boardId, drafts: List<EventDraft>): List<OneOffEvent>`
  — board membership check, persists rows.
- For each new row, build a `CalendarEvent` (the existing data class) and
  call `CalendarInvitationComposer.sendInvitation(to = listOf(userEmail), …)`.
  Use `oneOffEvent.id.toString()` as the `ical_uid` so a future cancel can
  re-send with `METHOD:CANCEL`.

`QuickAddFlow.save` becomes batch-aware: walk `state.items`, route tasks to
`BacklogTaskService.createTask` and events to `OneOffEventService.createEvents`.
Wrap in a single try; if any item fails, abort the batch and surface
`quickadd.failed` — same UX as today's single-task failure. (No partial
success in v1.)

The user's email comes from `UserAuthService` / settings (same lookup
`PlanInviteDispatcher` does today). If the user has no verified email,
fall back to creating the row but skipping the invite, and warn in the
confirmation: "saved, but couldn't email a calendar invite — verify your
email in settings." Telegram is the entry point; not every Telegram user
has email yet.

### 4. Card rendering

`QuickAddFlow.renderCard` currently renders one task draft. Generalise to
render N items, one block per item:

- Task block: unchanged (`➕ title`, meta line, tags, description).
- Event block: `📅 title`, `🕒 Wed Mar 12 · 19:30–20:30 IDT`, optional
  `📍 location`, optional notes preview.

Buttons stay the same triple (Save / Adjust / Cancel). Adjust still revises
the whole batch — per-item adjust is deferred.

i18n: new keys (`quickadd.card.event.*`, `quickadd.event.location`,
`quickadd.event.time`, etc.) across all 11 `messages_*.properties`. Mostly
mechanical.

### 5. Planner drawer integration

Existing weekly plan drawer has a section for external/calendar items.
Wire a backend endpoint `GET /api/v1/plans/{week}/external-events` (or
extend the existing weekly-plan payload) that returns one-off events whose
`starts_at` falls inside the requested ISO week. Read-only.

Frontend renders them in that section as-is — no disclaimer; the user
created them, so they already know what they're looking at. Clicking an
event is a no-op in v1 (or routes to a future detail/cancel modal).

### 6. Planning assistant context

`StubCalendarWindowProvider.describeWindow` currently returns the
placeholder "User has not connected their calendar; assume no fixed
commitments are known." Replace the stub with a real provider that lists
one-off events whose `starts_at` falls inside the requested window:

```
Known events on the user's calendar (partial — only events created
through Backlog.fyi; the user's other calendar entries are not visible):
- Wed Mar 12, 19:30–20:30 IDT — Parent-teacher conference (school auditorium)
- ...
```

The "partial" note belongs **here**, in the prompt, not in the drawer —
the assistant needs to know its view is incomplete so it doesn't
confidently propose time blocks that collide with the user's other
commitments. When the list is empty, fall back to today's placeholder
string so the prompt shape is unchanged.

This feeds straight into `WeeklyPlanningPromptAssembler` via the existing
`calendar_window` template variable — no prompt template churn, just a
new provider implementation behind the same interface.

### 7. Metrics

- `tasker.quickadd.outcome{result, item_kind}` — extend the existing
  counter with `item_kind=task|event|mixed` so we can see the split.
- Reuse `tasker.email.sent{purpose=scheduling, outcome=…}` — already wired
  by the existing invite path.

### 8. Tests

- `TaskSuggestionAgentTest`: a couple of fixtures where the model returns
  an event item, a mixed batch, and the legacy single-task shape (regression).
- `QuickAddFlowTest`: confirms multi-item rendering, batch save (both
  succeed), batch save with an event but no verified email (creates row,
  skips invite, surfaces the warning), partial-failure abort.
- `OneOffEventServiceTest`: board-membership guard, ical_uid stability,
  invite dispatch via mocked `CalendarInvitationComposer`.
- Liquibase changeset round-trip in both H2 and the existing Postgres
  integration test.

## Editing — the open question

Calendar clients treat an emailed `.ics` with `METHOD:REQUEST` as the
authoritative copy: once accepted, the user owns it in their calendar and
can drag, rename, or delete it locally. Our database row becomes
informational from that point on.

To make a backend edit propagate to the user's calendar we'd have to
re-send the invite with the same UID and a bumped `SEQUENCE` — exactly the
mechanism `PlanInviteDispatcher.dispatchUpdates` already uses. The
machinery is there; the v1 decision is just to not expose an edit UI yet.

**v1 stance**: events are write-once from the app's side. If the user
wants to change one, they edit it in their calendar (which works fine and
is what most people will do anyway), or they re-run `/add` and ignore the
stale row. A `cancel` button in the planner drawer (sets `cancelled_at`,
fires `dispatchCancellations`) is the obvious phase 2 — small, isolated,
worth doing once the v1 has bedded in.

## Phasing

1. Schema + entity + service + invite dispatch (no AI changes yet) — verifies
   the storage and email piece in isolation, behind a dev-only endpoint.
2. AI outcome widening + prompt changes + card rendering.
3. Planner drawer read-only listing.
4. Replace `StubCalendarWindowProvider` so the planning assistant sees the
   user's one-off events (with the "partial" caveat in the prompt).
5. (Later) Cancel button in the drawer.
6. (Later) Per-item Adjust, edit-from-drawer, recurrence — only if real
   usage demands it.
