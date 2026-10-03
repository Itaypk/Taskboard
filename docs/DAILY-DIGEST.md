# Daily digest

Status: **v1 implemented.** Tracked in [#278](https://github.com/Itaypk/Taskboard/issues/278). This doc
is the source of truth for the design.

## Why

Beta users expected that giving a task a deadline means they'd hear about it when the deadline
arrives, even if the task never made it into a weekly plan. The only app-driven notification so far
was the slot reminder ([`docs/NOTIFICATIONS.md`](NOTIFICATIONS.md)), which only fires for planned
time blocks. An unplanned task with a deadline therefore stayed silent.

`docs/SPEC.md` listed a daily digest as a non-goal ("might add noise"). The deadline feedback is the
reason to revisit that: one message a day turns out to be a better fit than per-task deadline
reminders.

- **Deadlines are dates, not times.** There's no natural moment to fire a per-task reminder, so
  we'd have to pick a time of day anyway. A batched message is the obvious fit for that.
- **Less noise.** Five tasks due on the same day produce one message, not five.
- **No state to keep in sync.** The digest is computed when it's sent. Per-task reminder rows would
  have to follow every deadline edit, completion, archive, board move and recurrence roll.

## What the user gets

One Telegram message per day, at a time and on days the user chooses. It has two sections:

- **Today:** tasks in the current week's plan that have a time block today, with the block's start
  time. Slot reminders still fire before each block; this section is an overview, not a replacement.
- **Due:** open tasks that are **not** in the current plan and whose deadline is today or earlier.
  Overdue tasks are listed until they're done, rescheduled, planned or muted. The list is capped at 10
  items, with "+N more" beyond that.

If both sections are empty, nothing is sent.

Below the message there are up to four buttons:

| Button | Shown when | Effect |
|---|---|---|
| 👍 Got it | always | Nothing; acknowledges the message. |
| 🗓 Revisit the plan | AI is available to the user | Runs the same flow as `/plan`: keep, revise or start over for the current week, or choose a week when there's no plan yet. |
| 🔕 Remind me next week | the digest lists due tasks | Mutes the **listed** due tasks until the current plan week ends. |
| 🔕 Don't remind me again | the digest lists due tasks | Mutes the **listed** due tasks for a year. |

The mute buttons are worded as being about due tasks only. They never affect the Today section or
slot reminders.

## Decisions

### Templates only, no AI copy

The digest is a list. An AI rewrite would add token spend and a failure mode for little gain, so
the digest is rendered from `MessageSource` templates only. If that changes, the existing
`aiEnhancedReminders` setting can gate it, with no schema change.

### Schedule: a cron expression, like weekly planning

`user_settings.daily_digest_cron` uses the same Spring 6-field format as `planning_cron`, evaluated
in the user's time zone. One field holds both the time and the days of the week, so "weekdays at
08:00" is `0 0 8 * * MON-FRI` and the settings UI composes it from a time input and day chips.

Validation is stricter than for `planning_cron`: the expression must have the shape
`0 <minute> <hour> * * <days>`, i.e. **at most once a day**. The UI only produces that shape. The
check exists so a hand-written or imported expression like `* * * * * *` can't turn the digest into
a spam loop.

### Delivery: polling with a per-user watermark, not a per-user `CronTrigger`

`DailyDigestScheduler` runs every five minutes. For each user with the digest enabled it computes
the next fire time after `user_settings.daily_digest_last_run_at`. If that time has passed, it
advances the watermark and then composes and sends the digest. This mirrors why slot reminders poll
(see `docs/NOTIFICATIONS.md`, "Why polling"):

- **Restart-safe for free.** No in-memory future to rebuild; time-zone and cron edits take effect
  on the next tick without re-registration.
- **Never sends twice.** The watermark is committed *before* the send, so a crash mid-send loses
  that day's digest rather than duplicating it. For a daily summary that's the right trade.
- **No backlog after downtime.** If the computed fire time is more than three hours old (downtime,
  or a deploy that skipped a tick), the run is skipped and only the watermark advances. A morning
  digest at 4pm is noise.
- **First sighting doesn't fire.** A user with no watermark (every user, right after this ships)
  just gets the watermark set to "now". The first digest goes out at the next scheduled time, not
  on deploy.

A failed send is logged and counted but not retried; the next digest is a day away anyway.

The watermark is a "last evaluated" timestamp, not "last sent": it also advances on days the digest
is empty or skipped. The `daily_digest` table (below) is the record of what was actually sent.

### Gate

Delivered only when **all** of these hold:

- `daily_digest_enabled` is on. Default **on** for everyone, including existing users.
- The user has a deliverable push channel (`ScheduledConversationChannelResolver`; Telegram today).
  This is the same gate slot reminders use, and it's why "on by default" only reaches users who can
  actually receive it.
- The digest has something to say.

The digest has its own toggle and doesn't depend on `app_reminders`: one is a daily summary, the
other a nudge before each block.

`daily_digest_due_tasks` (default on) turns the Due section off for users who only want the
Today overview.

### Mutes are per user and tied to the deadline the user saw

`deadline_reminder_mute` has one row per `(user_id, backlog_task_id)`, holding the `deadline` that
was muted and `muted_until` (exclusive).

- **Per user, not a task column.** A task on a shared board belongs to the board. One member
  muting it must not silence it for the others, so the mute can't live on `backlog_task`.
- **Tied to the deadline.** A mute applies only while the task's deadline still equals the muted
  `deadline`. Moving the deadline, or a recurring task rolling forward to its next occurrence
  (`RecurrenceCalculator`), turns reminders back on automatically. With a plain flag, a muted task
  would stay silent forever and nobody would remember why.
- **"Next week"** means until the start of the next plan week, using the user's `week_start_day`.
  That's the same week boundary `/plan` uses, whether or not there's a plan this week.
- **"Don't remind me again"** is a year. Combined with the deadline tie, that's "until something
  about this task changes".
- **Re-enabling:** Settings has a "turn due-task reminders back on" action
  (`DELETE /api/v1/settings/deadline-mutes`) that clears all the user's mutes.

### Buttons act on what the user saw

Each sent digest is recorded in `daily_digest` (id, user, local date, sent time), and the due tasks
it listed are stored in `daily_digest_due_task` together with the deadline shown. Button callback
data is `dig:<code>:<digestId>`, the same self-describing, DB-backed routing as slot reminders'
`rem:<code>:<notificationId>` (well under Telegram's 64-byte limit). A tap reloads the digest row,
checks it belongs to the user, and mutes exactly the listed tasks at the listed deadlines.

Recalculating the list at tap time would be simpler but wrong: a task edited between the send and
the tap could get muted without the user ever having seen it in the digest.

The two tables store identifiers, dates and timestamps only. No task titles, so nothing needs
encryption. Titles are decrypted when the message is composed and live only in the sent message.

### "Revisit the plan" reuses `/plan`

The button takes the same path as typing `/plan` (`PlanBotCommand`). That command already handles
all three cases: a planning conversation in progress, a finalized plan for this week (keep, revise
or start over), and no plan yet (choose this week or next). The digest handler only signals "start
planning"; `TelegramChannel` dispatches `/plan`, because planning conversations are bound to a
Telegram chat. The button is only offered when AI is available to the user, matching
`PlanBotCommand.requiresAi`.

## What's in the digest, precisely

Computed in `DailyDigestComposer` at send time, in the user's time zone:

- **Current plan:** `PlanningSessionService.findCurrentPlan`, the finalized plan for the week that
  contains today.
- **Today:** that plan's slots (`PlannedTaskService.findForSession`) whose start falls on today.
  Tasks that are now done, archived or deleted are dropped. Titles come from the live backlog task,
  not the planning snapshot, so a rename shows up. Ordered by start time.
- **Due:** across every board the user belongs to:
  - status `TODO`
  - `deadline <= today`
  - not in the current plan
  - not a tutorial task
  - assigned to nobody or to the user (a task assigned to another member is theirs to worry about)
  - not under an active mute

  Ordered by deadline (oldest first), then priority (high first). Unlike the AI read paths, tasks
  hidden from the assistant **are** included: hiding a task from the AI shouldn't hide its deadline
  from its owner.

## Schema (changeset `016-daily-digest.xml`)

- `user_settings`
  - `daily_digest_enabled BOOLEAN NOT NULL DEFAULT true`
  - `daily_digest_due_tasks BOOLEAN NOT NULL DEFAULT true`
  - `daily_digest_cron VARCHAR(64) NOT NULL DEFAULT '0 0 8 * * *'`
  - `daily_digest_last_run_at TIMESTAMP NULL`
- `daily_digest`: `id`, `user_id`, `digest_date DATE` (local), `sent_at`. Unique on
  `(user_id, digest_date)` as a backstop against a double send.
- `daily_digest_due_task`: `digest_id` (FK, cascade), `backlog_task_id`, `deadline`.
- `deadline_reminder_mute`: `id`, `user_id`, `backlog_task_id`, `deadline`, `muted_until`; unique on
  `(user_id, backlog_task_id)`.

None of these is sensitive (identifiers, dates, a cron string), so none is encrypted. Like
`scheduled_notification`, the new tables have no foreign key to `backlog_task`. A deleted task
leaves harmless orphan rows that never match again. `AccountService.deleteUserData` clears all
three tables. The three new settings are part of the account export/import, with defaults so
version-3 exports from before this change still import. Mutes and the digest log are operational
state and aren't exported.

## Metrics

- `tasker.notification.sent{type=daily_digest, channel=telegram, outcome=success|failure|skipped, content=static|none}`,
  the same counter slot reminders use.
- `tasker.notification.action{type=daily_digest, action, outcome}` for button taps.

## Not in v1

- **Email or web delivery.** Telegram is the only push channel; web push is #261.
- **Muting a single task.** The mute buttons apply to every due task in that digest. With the
  deadline tie and the settings reset, that seemed acceptable for v1. Revisit based on feedback.
- **A "muted" marker in the task drawer.**
- **AI-written digest.** See above.
