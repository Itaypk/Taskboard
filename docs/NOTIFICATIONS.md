# App-driven notifications

Status: **Phase 1 implemented** (slot-reminder trigger: durable queue, scheduler, no-op handler
stub — see [Phase 1](#phase-1--the-trigger-implemented)). **Phase 2 planned, not started** (the
handler / actual delivery), split into [Phase 2a](#phase-2a--static-telegram-delivery) (static
one-way Telegram reminders end-to-end) and [Phase 2b](#phase-2b--ai-generated--interactive-reminders)
(AI-generated, interactive reminders). This doc is the source of truth for the design.

## Goal

When a user plans their week, each chosen task gets one allocated time slot (`planned_task_slot`,
ISO-8601 with offset). Today the only reminder is the **calendar invite**: `CalendarInvitationComposer`
embeds a `TRIGGER:-PT15M` VALARM in the iCal attachment and relies on the user's calendar client to
pop it. That has three gaps we want to close:

1. **Not sticky.** The calendar pop-up disappears when the block ends, and we deliberately don't
   create hours-long events to keep it visible.
2. **Email-only.** Invites only reach users with a verified email; channel-less / Telegram-only
   users get nothing.
3. **Dumb.** A calendar VALARM can't carry focused, AI-driven content (encourage, respond, offer to
   reschedule).

The system is built in two halves so the *trigger* (knowing a reminder is due) is fully decoupled
from the *handler* (delivering it). Phase 1 ships the trigger end-to-end with a no-op handler; Phase 2
fills in delivery.

Simplifying assumptions (still true): a **small number of users** and a **single-instance** server,
and the user is fine if a notification doesn't land exactly on time — *close enough* is acceptable.

## Design overview

```
PlanFinalizationService.applyPlan / addTaskToSession
        │  (added / removed slots, same (taskId|startIso) key as calendar invites)
        ▼
SlotReminderService.sync(...)  ──►  scheduled_notification rows (PENDING, fire_at = start − 15m)
                                            ▲
NotificationScheduler  ──@Scheduled(fixedDelay 60s)──► poll PENDING where fire_at ≤ now
        │  per row: past slot start? → EXPIRED ; else publish event → SENT
        ▼
SlotReminderDueEvent  ──►  @EventListener handler   (Phase 1: log-only stub; Phase 2: deliver)
```

### Why polling, not a per-slot `CronTrigger`

`PlanningSessionScheduler` registers a per-user `CronTrigger` on a shared `TaskScheduler` for the
*recurring* weekly-planning prompt. A reminder is different: it fires **once**, at a slot-specific
time. The codebase's other scheduling idiom — `@Scheduled(fixedDelay=…)` DB-polling (`ActivityTracker`,
`UnclaimedAccountCleanupService`) — fits one-off fire times far better:

- **Restart-safe for free.** State is the `PENDING` rows in the DB, re-derived every tick. After a
  restart (or downtime) the poller simply resumes; there is no in-memory `ScheduledFuture` map to
  rebuild and no `ApplicationReadyEvent` re-registration step.
- **Scales to a dynamic set of distinct fire times** without juggling one future per slot.
- The *close-enough* tolerance makes a ~1-minute cadence perfectly adequate.

## Phase 1 — the trigger (implemented)

All new code lives in package `dev.itayp.tasker.notification`.

### The queue: `scheduled_notification`

`ScheduledNotificationEntity` / changeset `008-scheduled-notifications.xml`. One row per pending
notification. Columns hold **identifiers and timestamps only** — `user_id`, `session_id`,
`backlog_task_id`, `slot_start_iso`, `slot_end_iso`, `type`, `fire_at`, `status`, `created_at`,
`sent_at`. Crucially it stores **no task title or notes**, so nothing here needs at-rest encryption;
the handler resolves and decrypts user-authored content at delivery time. Indexes: `(status, fire_at)`
for the poll query, and `(user_id)` for account-deletion cleanup.

- `NotificationStatus`: `PENDING` → `SENT` | `CANCELLED` | `EXPIRED`.
- `NotificationType`: `SLOT_REMINDER` (room left for future kinds).
- `ScheduledNotificationRepository`: a bounded due-row query
  (`findByStatusAndFireAtLessThanEqualOrderByFireAtAsc`), a `cancelPending` bulk update (JPQL enum
  literals), and `deleteByUserId`.

### Materialization: `SlotReminderService`

`sync(userId, sessionId, previous, current)` keeps reminders in lock-step with a finalized plan's
slots. It identifies a slot by `"${taskId}|${startIso}"` — the **same key** the calendar UID and
`PlanFinalizationService.computeInviteDiff` use — so the two stay consistent:

- **Added** slot → insert a `PENDING` row with `fire_at = OffsetDateTime.parse(startIso) − 15m`
  (matching the iCal VALARM). Skipped if that instant is already in the past.
- **Removed** slot → `cancelPending` (marks its `PENDING` row `CANCELLED`).
- **Changed** (same start, different end/label/title) → no-op: the fire time depends only on start.
  (A *time move* reads as removed-old + added-new, exactly like the invite diff.)

Unlike calendar invites, reminders are created **regardless of email eligibility** — reaching
channel-less / email-less users is the whole point. Wired into `PlanFinalizationService.applyPlan`
(covers `complete` + `revisePlan`) and `addTaskToSession`.

### Firing: `NotificationScheduler`

`@Scheduled(fixedDelay = 60s)` `poll()` fetches a bounded batch of due `PENDING` rows. Per row,
fail-soft in its own try/catch:

- If the slot start has **already passed**, mark `EXPIRED` (a "15-min-before" nudge after the fact is
  useless) — this also absorbs reminders that came due during downtime.
- Otherwise publish `SlotReminderDueEvent` and mark `SENT`.

`SlotReminderDueEvent` carries identifiers only (no sensitive payload), mirroring the existing
`UserPlanningScheduleChangedEvent` style.

### The seam: `SlotReminderLogListener`

A single `@EventListener` that just logs (UUIDs only). This is exactly where Phase 2 plugs in real
delivery; keeping it the sole subscriber preserves the clean trigger/handler split.

### Housekeeping

`AccountService.deleteUserData` clears `scheduled_notification` rows for the user.

### Tests

- `SlotReminderServiceTest` — added→queued at −15m; removed→cancelled; unchanged & same-start→no
  churn; past-fire→skipped (Mockito + `Clock.fixed`).
- `NotificationSchedulerTest` — due→event + `SENT`; past-start→`EXPIRED`, no event; one row throwing
  doesn't stop the batch.

### Known limitations (intentional for Phase 1)

- **No backfill.** Only plans finalized/revised *after* deploy get reminders; pre-existing plans
  have no rows.
- **No delivery and no gating yet.** Reminders are materialized for everyone and only logged. The
  per-user opt-in and "does this user have a deliverable channel?" check belong with the handler
  (Phase 2), so today we queue rows that will currently only ever be logged.
- **Fixed 15-minute lead time** (a constant, not yet configurable).

## Phase 2 — the handler (delivery)

Phase 2 turns the no-op `SlotReminderLogListener` into a real dispatcher: it resolves the user's push
channel, decides whether the user should actually be notified, renders the message, sends it, and
records the outcome. It ships in two increments:

- **[Phase 2a](#phase-2a--static-telegram-delivery)** — a static, localized, one-way Telegram
  reminder, end-to-end: opt-in resolution, channel gating, content rendering, delivery, status
  lifecycle, and metrics. This is the whole "actually reach the user" path.
- **[Phase 2b](#phase-2b--ai-generated--interactive-reminders)** — richer content: an AI-generated
  nudge with inline actions (done / snooze / reschedule), gated on the user's existing `aiEnabled`
  preference so AI-averse users keep the static 2a message permanently.

### Cross-cutting decisions

- **Gate at delivery, not materialization.** The queue stays a faithful mirror of the plan;
  whether a row is actually sent is decided when it fires. This is required (not just preferred)
  because the opt-in default depends on email eligibility, which can change between materialization
  and fire time (see below).
- **Opt-in default = "on only when the user has no calendar-invite email."** Model the preference as
  a **nullable** `appReminders: Boolean?` on `UserSettings` (parallel to `calendarInviteEmail`):
  - `null` (**auto**, the default) → enabled **iff** the user has no working calendar-invite email,
    i.e. `InviteDeliveryResolver.resolveEmailContext(userId) == null`. This auto-reaches channel-less
    / email-less users (the original motivation) without double-notifying users who already get an
    email invite + its VALARM.
  - `true` / `false` → explicit user override, regardless of email.
- **The handler owns the terminal status, not the scheduler.** Today `NotificationScheduler` marks a
  row `SENT` the moment it publishes the event — before any delivery has happened (a Phase 1
  limitation). Phase 2 moves that transition into the dispatcher so a failed send is never recorded
  as sent. See [Status lifecycle](#status-lifecycle--robustness).

## Phase 2a — static Telegram delivery

### The dispatcher

Replace `SlotReminderLogListener` with `SlotReminderDispatcher` — still the **sole** `@EventListener`
for `SlotReminderDueEvent`, preserving the trigger/handler split. On each event it:

1. **Resolves eligibility** via a new `ReminderDeliveryResolver` (modelled on `InviteDeliveryResolver`,
   the single home of the gate): applies the `appReminders` auto/override rule above, and confirms a
   deliverable push channel exists (`ScheduledConversationChannelResolver.hasDeliverableChannel`).
   Not eligible → mark `SKIPPED` (a distinct terminal state so metrics stay honest; not a failure).
2. **Resolves content.** Looks up the task via `BacklogTaskService.findTask(userId, backlogTaskId)`
   (the user-scoped bridge that returns the *decrypted* title). If the task no longer exists, mark
   `SKIPPED`.
3. **Renders** a localized string through Spring `MessageSource` with `UserSettingsService.getLocale`
   (e.g. key `notification.slot_reminder.text` with the title and start time), exactly like the
   planning conversation and email invites. The decrypted title lives only in the rendered message —
   never written back to `scheduled_notification`.
4. **Sends** via the channel from `ScheduledConversationChannelResolver.resolve(userId)` — a
   `ConversationChannel.send(ChannelMessage.Text(...))` (one-way; no inbound expected in 2a).
5. **Records the outcome** (below) and increments a metric.

### Opt-in setting & UI

- New nullable `app_reminders` column on `user_settings` (additive Liquibase changeset; a plain
  boolean — not sensitive, so no encryption), surfaced on the `UserSettings` model and
  `UpdateUserSettingsRequest`.
- Settings UI: a control next to the calendar-invite toggle. Because the setting is tri-state
  (auto / on / off), present it as either three options or an explicit on/off whose default label
  explains the auto behaviour ("Automatic — on when you don't get email invites").

### Status lifecycle & robustness

Statuses today: `PENDING → SENT | CANCELLED | EXPIRED`. Phase 2a adds `SKIPPED` and `FAILED`, plus an
`attempts` counter:

```
PENDING ─ slot start passed ───────────────────────────────► EXPIRED
        ─ plan slot removed (SlotReminderService) ──────────► CANCELLED
        ─ fired, but not eligible / task gone ──────────────► SKIPPED
        ─ fired, sent OK ───────────────────────────────────► SENT
        ─ fired, send failed (attempts < max) ──────────────► PENDING (retried next poll)
        ─ fired, send failed (attempts == max) ─────────────► FAILED
```

- The scheduler stops setting `SENT`; it only flips `EXPIRED` for past-start rows and publishes the
  event. The dispatcher sets `SENT` / `SKIPPED` / `FAILED` in its **own** transaction (so the
  outcome survives independent of the poll), and a transient send failure leaves the row `PENDING`
  for the next ~1-minute poll to retry — naturally bounded, because once the slot start passes the
  `EXPIRED` gate retires it. `attempts`/`FAILED` caps pathological retries and gives a metric to
  alert on.
- **Idempotency / at-scale note.** A crash *after* a successful Telegram send but *before* the status
  commit could re-send on the next poll. At single-instance, small-user scale this is acceptable; if
  it ever matters, claim the row first (`PENDING → SENDING`, committed) and dispatch from an
  `@TransactionalEventListener(AFTER_COMMIT)` with a sweep for stale `SENDING` rows.

### Metrics

A `tasker.notification.sent` counter tagged `type` (`slot_reminder`), `channel` (`telegram`), and
`outcome` (`success` / `failure` / `skipped`), mirroring the email senders' `tasker.email.sent`
(`EmailMetricsOutboundChannel`). Hook `outcome=failure` to a Grafana alert.

### Tests

- `ReminderDeliveryResolver` — the auto rule (no email → enabled; has email → disabled) and explicit
  overrides; no deliverable channel → ineligible.
- `SlotReminderDispatcher` — eligible → renders localized text + sends + `SENT`; opted-out / no
  channel / missing task → `SKIPPED`, no send; send throws → `PENDING` then `FAILED` at the cap.
- `NotificationScheduler` — adjust existing tests: scheduler no longer sets `SENT` itself.

### Out of scope for 2a (→ 2b)

AI-generated copy, inbound replies / interactive actions, and any channel other than Telegram.

## Phase 2b — AI-generated & interactive reminders

> Sketch, not a committed design — to be detailed after 2a ships.

The dispatcher's content step becomes conditional on the user's existing AI preference
(`UserSettings.aiEnabled`, and `aiTier`): **AI-off users always get the static 2a message**; AI-on
users get a richer one.

- **AI content.** Generate a focused nudge with `AiClient` / `AiConversationManager` — encourage,
  summarize the task, set the tone from the user's context — instead of the static template. Subject
  to the same AI access controls (`AiAccessService` / `AiUsageTracker`) as the planning conversation,
  so reminders draw from the same budget/metering.
- **Interactive turn.** Send a `ChannelMessage.Choice` (inline buttons) for *mark done / snooze /
  reschedule*, and route the user's reply back into an AI conversation. This needs an inbound-routing
  registry analogous to `TelegramSessionRegistry` (which already maps a Telegram chat to an active
  planning session) so a reminder reply reaches the right handler — the one genuinely new piece of
  plumbing. *Reschedule* in particular reopens the planner, which is still user-scoped today
  (`docs/BOARD-SHARING-PHASE1.md`).
- **Open questions.** Conversation lifetime / timeout for an unanswered reminder; whether *snooze*
  re-queues a `scheduled_notification` row (it naturally can) vs. an in-memory delay; how an AI nudge
  degrades if the AI call fails (fall back to the static 2a message — keeps delivery robust).

## Beyond slot reminders

`NotificationType` is an enum on purpose. Other kinds — e.g. a deadline approaching, a weekly plan
ready to review, a stale-task nudge — can ride the same queue + poller + event seam. Each new type
adds a materializer (the equivalent of `SlotReminderService`) and a branch in the dispatcher; the
scheduler, table, and status lifecycle are reused unchanged.
