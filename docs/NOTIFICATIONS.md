# App-driven notifications

Status: **Phase 1 implemented** (slot-reminder trigger: durable queue, scheduler, no-op handler
stub — see [Phase 1](#phase-1--the-trigger-implemented)). **Phase 2 not started** (actual delivery:
channel resolution, localized/AI copy, Telegram push, opt-in — high-level only, see
[Phase 2](#phase-2--the-handler-high-level-only)). This doc is the source of truth for the design.

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

## Phase 2 — the handler (high-level only)

> Not yet planned in detail. These are the directions, not commitments.

The `SlotReminderDueEvent` listener becomes a real dispatcher. Rough shape:

- **Channel resolution.** Reuse the existing abstractions — `ScheduledConversationChannelResolver`
  already owns "does this user have a deliverable (Telegram) push channel?", and
  `TelegramConversationChannel` / `OutboundChannel` already send. Telegram is the first delivery
  channel; email could follow for users who prefer it.
- **Opt-in & gating.** A per-user preference parallel to `calendarInviteEmail` (e.g. a settings flag),
  checked at delivery time. Open question worth deciding early: gate at **materialization** (don't
  even queue rows for opted-out users) vs. at **delivery** (queue everything, filter when firing).
  Delivery-time gating is more flexible (the queue stays a faithful mirror of the plan) at the cost
  of some dead rows.
- **Localized copy.** Render via Spring `MessageSource` with the user's `preferredLanguage`
  (`UserSettingsService.getLocale`), like the Telegram planning conversation and email invites.
- **AI-driven content.** The richer motivation: instead of a static "your block starts soon," use the
  `AiClient` / `AiConversationManager` to generate a focused nudge — encourage, summarize the task,
  and offer inline actions (mark done, snooze, reschedule). The reminder could open a short
  interactive turn over the existing `ConversationChannel`, since Telegram already handles inbound
  callbacks.
- **Delivery robustness.** Decide retry/idempotency semantics once delivery can actually fail (today
  the row is marked `SENT` as soon as the event is published). Consider a `FAILED` status + bounded
  retry, and metrics (a `tasker.notification.*` Prometheus counter) hooked to a Grafana alert, as the
  email senders already do.
- **Beyond slot reminders.** `NotificationType` is an enum on purpose — other kinds (e.g. deadline
  approaching, weekly-plan ready) can ride the same queue + poller + event seam without new plumbing.
