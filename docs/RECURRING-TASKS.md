# Recurring tasks

Status: **Design — not implemented.**

Recurring tasks are tasks that come back at regular intervals: an annual dentist checkup, cleaning
the vacuum filters, changing the car's oil, paying rent.

**Product guardrail.** Recurrence only decides *when a task comes back into the backlog*. The
weekly planner still decides when in the week it gets done. There are no calendar series, no
reminders for each occurrence, and no end dates. That keeps the feature out of the "classic
calendar" territory `IDEAS.md` worried about.

## Decisions at a glance

| Question | Decision |
|---|---|
| Storage | One `backlog_task` row with nullable recurrence columns that **rolls forward** on completion. No template table and no spawn job. |
| History | Each completion saves a **DONE copy** of that occurrence, linked back to the original. |
| Anchor (done date vs. schedule) | **Set by the rule type.** "Every N days/months" counts from completion; "weekly/monthly/yearly on X" follows the calendar. |
| When it reappears on To-do | On the occurrence date. The existing `relevant_from` future-date filter hides it until then. |
| Deadline | `due_within_days` relative to the occurrence. It's written out as an absolute `deadline` on every roll. |
| Pills | Replace **Done** with **Recurring**: `To-do · Week · Recurring · All`. |
| Privacy | Recurrence columns are plaintext (schedule metadata, like `deadline` / `relevant_from`). |

## Core mechanism: one row that rolls forward, no background job

A recurring task is an ordinary `backlog_task` row with a recurrence rule. Its own status is never
set to `DONE`. When it's marked done, `BacklogTaskService.updateTask` does this instead:

1. **Saves a completed copy of the occurrence.** This is a new row with status `DONE` and the same
   title, description, URL, priority, estimate, category and tags (reusing the task-duplicate code).
   It also has `recurrence_kind = NULL` and `recurrence_source_id = <original id>`, keeps the
   occurrence's own `relevant_from` (when it triggered) and `deadline`, and gets
   `last_completed_on = today`.
   - The UI shows a localized note built from those columns: *"Occurrence of Sep 25 · completed
     Sep 27"*. Nothing is written into the encrypted description, so no language has to be chosen
     at write time and text the user wrote isn't touched.
   - The `STATUS_CHANGED TODO→DONE` change event is recorded **on the copy**, with **no `CREATED`
     event**. That way the created-task count doesn't grow. The planner's weekly diff
     (`BacklogTaskChangeService.summarize`) and event-based stats see exactly one completion per
     occurrence.
   - The copy is an ordinary done card under **All**, and `TaskAutoArchiveService` archives it
     like any other DONE task.
2. **Cancels pending slot reminders and calendar invites** for the original, reusing the
   `taskCompletionCancellationService.cancelUpcomingSlots` branch that already runs on completion.
3. Sets the original's `last_completed_on = today`. "Today" is in the completing user's timezone,
   resolved the same way `filterOutFutureDated` does it.
4. Sets `relevant_from = next occurrence` and `deadline = next occurrence + due_within_days`
   (or `null`).
5. Clears `last_scheduled_in_session_id`, so the task leaves the Week pill. Resets
   `reschedule_count = 0`, so one occurrence's urgency history doesn't carry into the next. The
   assignee is kept, so household chores keep their owner.
6. Leaves `status = TODO`.

From there, **existing** code hides the task until its next occurrence: the To-do view
(`BacklogTaskService.getTasks` → `filterOutFutureDated`) and the planner
(`PlannerTaskSelector.isPlannerVisible`) both skip tasks whose `relevant_from` is in the future.
Nothing has to "trigger" a task, so there's no scheduler, no restart concern, and no
duplicate-spawn guard. If a task sits open past its next date, the missed occurrences merge into
one.

**Where to hook it:** inside `updateTask`'s existing
`newStatus in TERMINAL_STATUSES && previousStatus !in TERMINAL_STATUSES` branch, when the task has a
recurrence rule and `newStatus == DONE`. That one place covers every way to mark a task done:

- the web UI's `PUT /api/v1/boards/{b}/tasks/{id}`,
- `BacklogTaskService.markDone`, used by the Telegram reminder menu (`ReminderActionHandler`) and
  planning reconciliation (`WeeklyPlanningOrchestrator` `RECONCILE_DONE`),
- the external API's `POST …/tasks/{id}/done`.

Archiving a recurring task does **not** roll it forward. Archiving means "stop this".

### Rejected alternative: templates + spawned copies

A recurring template (its own table, or a flagged row) that a daily job copies into ordinary tasks.
It needs a spawn scheduler, rules against spawning a copy while one is still open, a choice about
whether an edit hits the template or the copy, and a second encrypted content table. Its one
real advantage, a done card per occurrence, is what the completed copy above already provides at
a fraction of the cost. The difference: our copy is a *record created at completion*, never a live
instance.

## Recurrence rules

Deliberately simple:

| Kind | Fields | Anchor | Example |
|---|---|---|---|
| `EVERY_N_DAYS` | `every` 1..3650 | completion | vacuum filter every 45 days |
| `EVERY_N_MONTHS` | `every` 1..120 | completion | dentist every 6 months, oil change |
| `WEEKLY` | `day` 1..7 (ISO weekday) | calendar | put out recycling on Tuesday |
| `MONTHLY` | `day` 1..31 | calendar | pay rent on the 25th |
| `YEARLY` | `month` 1..12, `day` 1..31 | calendar | renew car insurance on 03-14 |

Plus `due_within_days` (0..365, nullable). In the UI it replaces the absolute deadline.

**Why the rule type decides the anchor:** interval rules describe maintenance ("6 months after the
last visit"), so the clock restarts at completion. Paying rent late shouldn't move next month's
rent, so day-of rules follow the calendar. This avoids an extra "count from…" toggle in an
already crowded drawer.

### Next-occurrence math

A pure `RecurrenceCalculator` that works only on `LocalDate`s:

- **Completion anchor:** `completedOn.plusDays(every)` / `completedOn.plusMonths(every)`.
  `plusMonths` clamps month-end: Jan 31 + 1 month = Feb 28/29.
- **Calendar anchor:** the first matching date **strictly after `max(currentOccurrence, completedOn)`**,
  where `currentOccurrence = relevant_from` (or `completedOn` if that's null).
  - Finishing early (possible from the Recurring pill) counts toward the current occurrence.
  - Finishing late skips occurrences that already passed; they merge into this completion.
- **Clamping:** `MONTHLY` uses `withDayOfMonth(min(day, lengthOfMonth))`, so day 31 also means
  "last day of the month". The UI labels it "31 (last day)". `YEARLY` Feb 29 falls on Feb 28 in
  non-leap years.

### Worked examples

**Pay rent:** `MONTHLY day=25, due_within_days=7`. The rule date is when the task *appears*, not
when it's due.

| Event | Result |
|---|---|
| Created in early September | appears Sep 25, due Oct 2 |
| Paid Sep 27 | copy saved (occurrence Sep 25, completed Sep 27); next appears Oct 25, due Nov 1 |
| Not paid until Nov 3 | next appears Nov 25; the October occurrence merges into this payment |

**Dentist:** `EVERY_N_MONTHS every=6, due_within_days=30`. Done Mar 10 → appears Sep 10, due
Oct 10. Done Oct 2 (late) → appears Apr 2.

### First occurrence

The drawer's date field becomes **"Next on"**. It's the same `relevant_from` column that "Available
from" uses today.

- Prefill: today for completion-anchored rules; the first matching date on or after today for
  calendar rules. So "yearly on 03-14", created in September, doesn't show up now.
- The user can edit it. For example, the last dentist visit was 4 months ago, so the next one is
  in 2 months.
- The server takes `relevant_from` as given and **recomputes `deadline`** from it on every create
  or update while a rule is set, ignoring any deadline the client sends.

`deadline` stays a stored absolute date. That's why `sort.ts`, planner urgency, deadline display,
and the AI prompts need no changes.

## Data model

Changeset `013-recurring-tasks.xml`, additive, all nullable, plaintext, on `backlog_task`:

| Column | Type | Notes |
|---|---|---|
| `recurrence_kind` | `VARCHAR(16)` | `NULL` = not recurring |
| `recurrence_every` | `INT` | interval kinds |
| `recurrence_day` | `INT` | weekday / day of month |
| `recurrence_month` | `INT` | `YEARLY` only |
| `due_within_days` | `INT` | |
| `last_completed_on` | `DATE` | on originals *and* completed copies |
| `recurrence_source_id` | `UUID` | self-FK, `ON DELETE SET NULL`, indexed; non-null = completed-occurrence copy |

- Validation lives in the service, not DB check constraints: the fields have to match the kind and
  stay in range. Failures are a 400 with a `code`; on the external API, the RFC 7807 `detail`
  names the allowed values.
- These are new columns on an existing table, so `AccountService.deleteUserData` needs nothing new.
- Export/import and board duplicate must remap `recurrence_source_id`, or drop it when the source
  task isn't included.

### API shape

The DTOs get a nested object; the DB stays flat:

```json
"recurrence": { "kind": "MONTHLY", "every": null, "day": 25, "month": null, "dueWithinDays": 7 },
"lastCompletedOn": "2026-09-27",
"recurrenceSourceId": null
```

`recurrence: null` means not recurring. `lastCompletedOn` and `recurrenceSourceId` are read-only.

### Hazard: PUT replaces the whole task

`PUT /api/v1/.../tasks/{id}` replaces the whole task, and several server paths rebuild a full
request from a task. **Any path that leaves out `recurrence` silently turns recurrence off.** Every
place that handles `relevantFrom` today is the checklist:

- `CreateBacklogTaskRequest`, `UpdateBacklogTaskRequest`, `TaskResponse` (`Responses.kt`)
- frontend `types.ts` `Task`, `TaskDrawer` form state
- the rebuilt requests in `BacklogTaskService.markDone` / `archive`
- task duplicate (`BacklogTaskService`) and board duplicate (`BoardService`)
- `AccountExportResponse` / `AccountImportService` (optional fields; follow the versioning rules
  in `export-format-v3.md`)
- `ExternalApiDtos`: add `"recurrence"` to `CLEARABLE_FIELDS`; PATCH replaces the object as a whole
- `UpdateTaskTool` (the LLM's update path)
- `BacklogTaskSearchAgent`, `WeeklyPlanningPromptAssembler`

Pin this with a test: an edit that doesn't touch recurrence must keep it.

## Lifecycle

- **Stop recurring:** turn the toggle off. The task becomes a normal task and keeps its current
  `relevant_from` / `deadline`. Archiving also works as the off switch. `TaskAutoArchiveService`
  only sweeps `DONE`, so a recurring original is never auto-archived.
- **Wrong "done" click:** v1 takes two manual steps. Archive the completed copy, then use **"Do it
  now"** on the recurring card, which sets `relevant_from = today` and recomputes `deadline`. A real
  undo toast is out of scope. It belongs with the "more satisfying mark as done" work
  (`IDEAS.md` → UI - Tasks), and must also undo the roll-forward there.
- **Reopening a completed copy** (mark to-do) turns it into a plain one-off task without touching
  the original's schedule. It's easy to understand, so it isn't blocked.
- **Deleting the original** leaves its copies alone (`recurrence_source_id` becomes null).
- **Shared boards:** the roll-forward date is computed in the completing member's timezone. Each
  viewer sees the future-date filter in their own timezone, as they do today.
- **Stats:** each occurrence adds one DONE copy row and one DONE event, so `StatsService`'s
  row-based `completedTasks` and its event-based throughput agree. The original never counts as
  done.

## Web UI

### Task drawer

- A **"Repeats"** `Toggle` (existing component) under the title/description block.
- When it's on:
  - A **rule row**: a kind select ("Every N days", "Every N months", "Weekly on", "Monthly on day",
    "Yearly on"), followed by the matching input: a number, weekday, day, or month + day. Weekday
    and month names come from `Intl.DateTimeFormat` in the UI locale.
  - The dates row (`row-2`) changes from *Deadline · Available from* to **Next on [date] · Due
    within [N] days**.
  - A plain-language summary line: *"Repeats 6 months after it's done · next Sep 10 · due within
    30 days"*.
  - Changing the kind suggests a new "Next on" date (client-side helper). The user can still
    change it.
- A completed copy shows its read-only occurrence note in place of the rule controls.

### Filter pills

`To-do · Week · Recurring · All`. This drops **Done** (as `IDEAS.md` already suggested for
mobile width).

- The server keeps `status=done` for the API. Done tasks, completed copies included, stay visible
  under **All**, and archived ones behind the existing archive toggle. Auto-archive stays a user
  setting.
- **Recurring** lists **all** recurring originals, both due now and waiting, sorted by next date.
  Completed copies aren't listed there.
- **No server change:** in `Board.tsx`, map `fetchStatus` `recurring → 'all'` (the same way
  `plan → 'todo'` works), then filter on `recurrence != null` in the client.

### Cards

- A small ↻ marker on recurring originals.
- When the next occurrence is in the future (seen under All / Recurring), show a *"Next: Sep 10"*
  badge instead of the deadline.
- Completed copies show the occurrence note.

### Mark done

- `handleMarkDone` must use the **PUT response** instead of assuming `status: 'done'`
  (`Board.tsx`), and add the completed copy to local state (or refetch).
- Play the leave animation, then show a toast: *"Done — back on Oct 25"*.
- The client-side `todo` filter must also drop tasks whose `relevantFrom` is in the future, so the
  rolled task leaves the view without a refetch.

### i18n

New keys (`boardFilter.recurring`, rule labels, summary, badge, occurrence note, toast) go into
**all four** `locales/*/translation.json` files, or `catalog.test.ts` fails. Hebrew and Arabic copy
must be gender-neutral, and new CSS must use logical properties.

## Other channels

- **Telegram:** the reminder menu's "mark done" confirmation mentions the next date (new
  `MessageSource` key in every bundle). Quick-add doesn't parse recurrence in v1.
- **Planner / AI:** `PlannerTaskSelector` is unchanged. Prompt task lines add something like
  `recurring=every 6 months`, so the model treats the task as routine upkeep and not something to
  push hard. `UpdateTaskTool` carries recurrence through unchanged; the assistant can't set it in
  v1.
- **External API:** add the fields to `openapi.yaml` and `SKILL.md`, and document that
  `POST …/done` on a recurring task returns `status: "todo"` with the next `relevantFrom`, plus
  the completed copy's id. The discovery surfaces (api-catalog, llms.txt, sitemap, link relations)
  are unaffected.

## Out of scope (v1)

- Nth-weekday rules ("second Tuesday of the month")
- Every N weeks (use N×7 days)
- End dates and occurrence counts
- Recurring calendar series / invites
- Recurrence parsed from quick-add or set by the assistant
- Advance reminders ("a week before")
- Undo toast (see Lifecycle)

## Suggested phasing

1. **Backend:** changeset, `RecurrenceCalculator`, roll-forward + completed copy in `updateTask`,
   DTOs across every path that rebuilds a request, tests.
2. **Web:** drawer, pill swap, card marker/badge/note, mark-done response handling, i18n.
3. **Channels:** Telegram copy, prompt annotation, external API contract + skill, export/import.

## Testing

- `RecurrenceCalculator` unit tests: month-end clamping, leap day, early and late completion,
  merged missed occurrences, each kind.
- `BacklogTaskServiceTest` roll-forward:
  - the DONE copy is created with its source link and occurrence dates;
  - the DONE event lands on the copy, with no CREATED event;
  - slots are cancelled;
  - the original's dates move, it stays TODO, and its session stamp is cleared.
- A full-replace preservation test (an edit that leaves out recurrence keeps it).
- External API: PATCH replaces the recurrence object, `clear: ["recurrence"]`, and the `/done`
  response shape.
- Frontend: drawer rule controls and summary, Recurring filter, mark-done toast, i18n catalog
  parity.
