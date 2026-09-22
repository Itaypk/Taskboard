# App-driven notifications

Status: **Phase 1 + Phase 2a + Phase 2b implemented.** Phase 1 is the slot-reminder trigger (durable
queue, scheduler, event — see [Phase 1](#phase-1--the-trigger-implemented)); [Phase 2a](#phase-2a--static-telegram-delivery)
is static, localized, one-way Telegram delivery; [Phase 2b](#phase-2b--ai-generated--interactive-reminders)
adds the interactive menu (ack / snooze / mark done) for every notified user and an AI-generated message
for AI-enhanced users. **Phase 2c not started** — [a free-form "let's discuss" follow-up
conversation](#phase-2c--lets-discuss-follow-up-conversation), under consideration. This doc is the
source of truth for the design.

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

### Known limitations

- **No backfill.** Only plans finalized/revised *after* the Phase 1 deploy get reminders; pre-existing
  plans have no rows.
- **Fixed 15-minute lead time** (a constant, not yet configurable).

(Delivery and per-user gating were Phase 1 gaps; they are now implemented in
[Phase 2a](#phase-2a--static-telegram-delivery).)

## Phase 2a — static Telegram delivery

Phase 2a replaces Phase 1's no-op log listener with a real dispatcher: it resolves the user's push
channel, decides whether the user should actually be notified, renders a localized message, sends it
over Telegram, and records the outcome. Static, one-way text only — AI/interactive content is
[Phase 2b](#phase-2b--ai-generated--interactive-reminders).

### Cross-cutting decisions

- **Opt-in is a plain boolean `appReminders` on `UserSettings`, defaulting to `true`** (parallel to
  `calendarInviteEmail`). The channel gate (below) means "on by default" only ever notifies users who
  actually have a Telegram channel, so it reaches the channel-less-user motivation without a more
  elaborate rule. (An earlier design made this a nullable tri-state keyed off email eligibility; it
  was simplified to a boolean.)
- **Gate at delivery, not materialization.** The queue stays a faithful mirror of the plan; whether a
  row is sent is decided when it fires, so a setting changed after planning takes effect immediately.
- **The handler owns every terminal status.** Phase 1's scheduler marked a row `SENT` the moment it
  published the event — before any delivery. Now the scheduler only finds due rows and publishes; the
  dispatcher owns `EXPIRED` / `SKIPPED` / `SENT` / `FAILED`, so a failed send is never recorded as sent.

### The dispatcher

`SlotReminderDispatcher` — the **sole** `@EventListener` for `SlotReminderDueEvent`, preserving the
trigger/handler split. It is `@Transactional` (the scheduler is deliberately *not*, so the dispatcher's
write runs in its own transaction). On each event it:

1. **Reloads the row** and ignores it unless still `PENDING` (idempotency guard).
2. **Checks timing:** if the slot start has already passed (or is unparseable) → `EXPIRED` (a
   "15-min-before" nudge after the fact is useless; this also absorbs reminders that came due during
   downtime).
3. **Resolves eligibility** via `ReminderDeliveryResolver` (modelled on `InviteDeliveryResolver`, the
   single home of the gate): `appReminders` on **and** a deliverable channel
   (`ScheduledConversationChannelResolver.resolve`). Not eligible → `SKIPPED` (a distinct terminal
   state so metrics stay honest; not a failure). The resolver returns a `ReminderContext`
   (channel + the user's locale + zone).
4. **Resolves content:** `BacklogTaskService.findTask(userId, backlogTaskId)` (user-scoped bridge,
   returns the *decrypted* title). Task gone → `SKIPPED`.
5. **Renders** `notification.slot_reminder` via Spring `MessageSource` in the user's locale, with the
   HTML-escaped title (`channel.formatter.escape`) and the start time formatted in the user's zone.
   The decrypted title lives only in the rendered message — never written back to the row or logged.
6. **Sends** `ConversationChannel.send(ChannelMessage.Text(...))` (one-way) and records the outcome.

### Opt-in setting & UI

- `app_reminders` `BOOLEAN NOT NULL DEFAULT true` on `user_settings` (changeset `009`; a plain boolean,
  not sensitive, so no encryption), threaded through `UserSettingsEntity` → `UserSettings` →
  `UpdateUserSettingsRequest` / `UserSettingsResponse`, the account export/import, and the frontend
  settings type.
- Settings UI: a second checkbox under "Notifications" in `SettingsModal` — "In-app reminders before a
  planned task starts (sent over Telegram, if connected)".

### Status lifecycle & robustness

Statuses: `PENDING → SENT | CANCELLED | EXPIRED | SKIPPED | FAILED`, plus an `attempts` counter
(changeset `009`):

```
PENDING ─ plan slot removed (SlotReminderService, at materialization) ─► CANCELLED
        ─ fired, slot start already passed ───────────────────────────► EXPIRED
        ─ fired, not eligible / task gone ────────────────────────────► SKIPPED
        ─ fired, sent OK ─────────────────────────────────────────────► SENT
        ─ fired, send failed (attempts < max) ────────────────────────► PENDING (retried next poll)
        ─ fired, send failed (attempts == max) ───────────────────────► FAILED
```

- A transient send failure leaves the row `PENDING`; the next ~1-minute poll re-selects it. Retry is
  naturally bounded — once the slot start passes it goes `EXPIRED`, and an `attempts` cap (`FAILED`)
  guards a permanently failing send and gives a metric to alert on.
- **Idempotency / at-scale note.** Events are published synchronously, so each row reaches a
  terminal/retry state before the next is processed. The only residual risk is a crash *after* a
  successful Telegram send but *before* the status commit (rare double-send). At single-instance,
  small-user scale this is acceptable; if it ever matters, claim the row first
  (`PENDING → SENDING`, committed) and dispatch from an `@TransactionalEventListener(AFTER_COMMIT)`
  with a sweep for stale `SENDING` rows.

### Metrics

A `tasker.notification.sent` counter tagged `type` (`slot_reminder`), `channel` (`telegram`), and
`outcome` (`success` / `failure` / `skipped`), mirroring the email senders' `tasker.email.sent`
(`EmailMetricsOutboundChannel`). Hook `outcome=failure` to a Grafana alert.

### Tests

- `ReminderDeliveryResolverTest` — `appReminders` off → ineligible; on but no channel → ineligible;
  on + channel → context with the user's locale/zone.
- `SlotReminderDispatcherTest` — eligible → renders localized text + sends + `SENT` + success metric;
  opted-out / no channel / missing task → `SKIPPED`, no send; slot start already passed → `EXPIRED`;
  already-terminal row → ignored; send throws → `PENDING` then `FAILED` at the cap.
- `NotificationSchedulerTest` — scheduler publishes an event per due row and writes no status itself.

## Phase 2b — AI-generated & interactive reminders

Phase 2b adds two independent things on top of 2a's one-way text: an **interactive menu** on every
reminder, and an **AI-generated message** for users who want it. The trigger (Phase 1) and the
status lifecycle are unchanged; all the new behavior lives in the dispatcher, a new action handler,
and the settings/UI plumbing.

### The interactive menu (all notified users)

The dispatcher now sends a `ChannelMessage.Choice` (the reminder text as the prompt + inline buttons)
instead of `ChannelMessage.Text`. Four options, for **every** notified user regardless of AI status:
**👍 ack/dismiss**, **✅ mark done**, **💤 snooze 1h**, **💤 snooze a day** (labels localized via
`MessageSource`).

- **Routing is DB-backed, not a registry.** Each button's callback data is self-describing —
  `rem:<code>:<notificationId>` (see `notification.ReminderAction`, kept under Telegram's 64-byte
  limit). `TelegramChannel` detects the `rem:` prefix on a callback `Selection` and hands it to
  `ReminderActionHandler`, which decodes the action and **reloads the row from the DB**. No in-memory
  map to keep in sync, and it survives a restart — consistent with the rest of the feature, whose
  state is the `scheduled_notification` table. The check runs ahead of the session / quick-add / plan
  registries because a reminder can land mid-session.
- **Actions** (`ReminderActionHandler`, channel-agnostic — replies go back through the same channel):
  - *ack* → a thumbs-up confirmation, no state change.
  - *snooze 1h / 1d* → `SlotReminderService.snooze(...)` inserts a **new** `PENDING` row (the original
    is already `SENT`) with `snoozed = true` and `fire_at = now + delay`, carrying the same task/slot
    identifiers. **Snoozing applies to the notification, not the calendar slot** (less friction/noise) —
    so the dispatcher **skips its "slot start already passed → `EXPIRED`" gate for `snoozed` rows**, and
    the re-delivery uses a time-less message variant (`notification.slot_reminder.snoozed`) since the
    original start is now in the past. Snooze re-queues land back on the same menu, so a user can snooze
    again or mark done. (Re-using `NotificationType.SLOT_REMINDER` + the flag, not a new enum value —
    the delivery path is identical bar the expiry skip.)
  - *mark done* → `BacklogTaskService.markDone(userId, taskId)`, a user-scoped bridge that resolves the
    task's board, sets it `DONE` (idempotent), and records the change + watermark. Gracefully reports
    when the task is gone.
- A `tasker.notification.action` counter is tagged `action` + `outcome` for observability.

### AI-generated copy (opt-out, AI-enabled users only)

- **Preference.** `UserSettings.aiEnhancedReminders` (`ai_enhanced_reminders`, changeset `009-3`,
  default `true`) — a plain boolean threaded through the entity / model / request / response /
  export-import / frontend, surfaced in settings **inside the AI fieldset** so it's only meaningful and
  visible when `aiEnabled` is on.
- **Gate.** `ReminderDeliveryResolver` returns `aiEnhanced = aiEnabled && aiEnhancedReminders` on the
  `ReminderContext`. The dispatcher additionally applies the **per-board** AI veto
  (`AiAccessService.isAiEnabledForBoard(task.boardId)`) before calling the model, so a shared-board
  co-member's opt-out is respected.
- **Generation.** `ReminderMessageAgent` — a single, stateless `AiClient.chat` (modelled on
  `TaskSuggestionAgent`; prompts in `prompts/slot-reminder/`) that writes a short warm nudge in the
  user's language. It runs under the same `AiCallGate` as everything else (user toggle + rolling-window
  tier budget), tagged `AiConversationType.SLOT_REMINDER` for usage accounting.
- **AI is an enhancement, never a delivery dependency.** Any failure — gate refusal, tier limit, empty
  reply, parse error — returns null and the dispatcher falls back to the static 2a template. AI vs
  static is recorded as a `content` tag on `tasker.notification.sent`. The model output (which carries
  the decrypted title) is escaped for the channel and never logged.

### Synchronous delivery vs. the AI call's latency

The AI call adds seconds of latency, which raised the question of moving `SlotReminderDueEvent` to
async handling. **We deliberately kept it synchronous.** The scheduler publishes events synchronously
and the `@Transactional` dispatcher processes the batch serially on the poller thread, so each row
reaches a terminal/retry state before the next event — the idempotency invariant 2a relies on. At a
handful of beta users with a ~1-minute cadence and "close-enough" tolerance, due-batches are near-always
0–2 rows; a slow batch just makes one poll run long and the next waits (`fixedDelay`), with no
correctness cost.

Going async would **break that invariant**: with a 60s poll, a handler that hasn't committed its status
yet can have its row re-selected and double-sent. The fix is the claim-then-dispatch pattern already
noted under 2a's *idempotency note* (`PENDING → SENDING` committed first, dispatch from
`AFTER_COMMIT`, plus a stale-`SENDING` sweep) — real machinery we don't need yet. **Upgrade trigger:**
many reminders coming due simultaneously *and* AI latency starting to delay other sends. (Pre-existing
caveat, unchanged by 2b: the dispatcher holds its transaction across the network send — and now the AI
call — which is fine at this scale but is the other reason to revisit if volume grows.)

### Tests

- `ReminderMessageAgentTest` — AI reply rendered; any failure returns null (→ static fallback).
- `SlotReminderDispatcherTest` — AI-eligible → AI `Choice` + `content=ai`; opted-out / AI-disabled →
  static `Choice` + `content=static`; AI throws → static fallback; `snoozed` row never `EXPIRED` even
  with a past slot start; the menu carries the four `rem:<code>:<id>` options.
- `ReminderActionHandlerTest` — ack → confirmation, no state change; snooze → new `snoozed` `PENDING`
  row at the right `fire_at`; mark done → task `DONE`; task gone / stale row → graceful notice.

## Phase 2c — "let's discuss" follow-up conversation

> Under consideration — captured here for future evaluation; not committed, and possibly not needed.
> **Current recommendation: don't build as specced — stop at 2b and validate demand first** (see
> *Assessment* below).

A fifth menu option — *"let's discuss"* — would start a free-form conversation off a reminder, letting
the user talk to the assistant in natural language to reschedule, restructure the task, or get
advice/encouragement. Open questions before committing:

- **Is it worth it?** It overlaps with the weekly-planning conversation; the value over "snooze / mark
  done / wait for the next plan" is unproven. This is the main reason it's deferred.
- **Inbound routing.** A reminder reply would need to reach an AI conversation, which means an
  inbound-routing registry analogous to `TelegramSessionRegistry` (chat → active conversation) — the
  one genuinely new piece of plumbing, since 2b's button routing is stateless/DB-backed.
- **Reschedule** in particular reopens the planner. The planner's candidate selection already
  spans a user's boards, but the planning session itself (and its slots) is deliberately
  user-scoped, not board-scoped — see `docs/BOARD-MODEL.md`.
- **Conversation lifetime / timeout** for an unanswered reminder, and how the conversation draws from
  the same AI budget/metering.

### Assessment (2026-06-23)

The cost/value ratio is the worst of any phase, and 2a + 2b already close all three gaps in the
[Goal](#goal) (sticky, channel coverage, AI-aware). 2c is the natural place to **stop**.

- **Value — thin, mostly already covered.** Reschedule is served by snooze-a-day + the weekly-planning
  conversation (the *designed* home for restructuring); restructuring is likewise planning's job. The
  only genuinely unique offering is in-the-moment advice/encouragement — soft and unproven. There's
  also a **timing mismatch**: the reminder fires 15 min *before* the task starts, precisely when the
  user is least available for a free-form chat; that use case fits planning time or a dedicated "talk
  to the assistant" entry better than a time-pressured reminder.
- **Bloat risk — high.** A fifth button loads choice friction onto the most glanceable, time-sensitive
  surface; a conversational surface is sticky (accrues memory/context/follow-up expectations, hard to
  walk back); and it splits "talk to the assistant" across two doors, weakening the planning
  conversation's role.
- **Engineering cost — highest so far, and it regresses a core property.** The inbound-routing registry
  reintroduces in-memory, restart-fragile state into a feature whose whole design (queue + poller +
  stateless `rem:<code>:<id>` routing) was deliberately DB-backed and restart-safe — an architectural
  regression, not just more code. Reschedule also reopens the (deliberately) user-scoped planner
  (`docs/BOARD-MODEL.md`). Plus conversation lifetime/timeout,
  multi-turn state, and AI-budget integration: effectively a second conversational agent, not a menu item.

**Recommendation.** Defer pending real user signal. If more in-the-moment flexibility is wanted, two
cheaper steps first: (1) **validate** — just ask the 2b beta users whether they ever wanted to "talk"
off a reminder before building a speculative surface; (2) if one more affordance is warranted, make it
**deterministic, not conversational** — e.g. "snooze: custom" or "bump to next weekly plan" — which
covers most of the reschedule motivation at near-zero cost and reuses 2b's stateless DB-backed routing
(no inbound registry).

## Beyond slot reminders

`NotificationType` is an enum on purpose. Other kinds — e.g. a deadline approaching, a weekly plan
ready to review, a stale-task nudge — can ride the same queue + poller + event seam. Each new type
adds a materializer (the equivalent of `SlotReminderService`) and a branch in the dispatcher; the
scheduler, table, and status lifecycle are reused unchanged.
