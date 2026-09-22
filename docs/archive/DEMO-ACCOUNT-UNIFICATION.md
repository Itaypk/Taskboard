# Demo account unification

**Status:** Draft / design — not yet implemented.
**Author:** (design discussion, 2026-06-13)

## Problem

The demo-account flow was designed when Telegram was the only login method. It creates a
deliberately throwaway account: a `users` row with `is_demo = true` and a hard 24-hour
`demo_expires_at`, no `auth_identities` row, a session capped to 24 h, pre-seeded with sample
tasks and a planning session (`DemoDataSeeder`). `DemoCleanupService` runs hourly and hard-deletes
any demo user past its expiry — **regardless of activity**, so a user actively using a demo loses
everything at the 24 h mark.

Now that login is decoupled from Telegram (multi-identity, `auth_identities`), the "demo vs real"
split has lost most of its justification. The valuable part — **one-click, zero-registration
start** — should stay. The throwaway framing should go.

## Goal

Retire the separate "demo" account concept. A zero-registration start creates a **real account**
that happens to have **no login identity yet**. The user is nudged to add an email or Telegram to
keep their data. Accounts that are never claimed and go inactive are still reclaimed automatically,
but on an **activity-based** schedule rather than a fixed wall-clock TTL.

Non-goals: changing the identity model, board model, or any authenticated-account behavior.

## Account lifecycle

Three signals on the `users` row drive everything. Two are one-way latches; one rolls forward.

| Signal          | Type                 | Set when                                             | Cleared |
|-----------------|----------------------|------------------------------------------------------|---------|
| `claimed`       | bool, **one-way**    | first identity is linked or a verified email is attached | never |
| `engaged_at`    | timestamp, **one-way** | first *genuine* write (see below)                  | never   |
| `last_active_at`| timestamp, rolling   | throttled, on any authenticated `/api/**` request    | n/a     |

Lifecycle states:

- **created** — `claimed = false`, `engaged_at = null`. Tire-kicker. **Short TTL.**
- **engaged** — `claimed = false`, `engaged_at` set. Did real work but hasn't added a login.
  **Long TTL** — enough runway to come back and claim.
- **claimed** — `claimed = true`. A normal account. **Never swept.**

`claimed` is the **hard safety guard**: the cleanup job can only ever touch `claimed = false` rows.
The short/long TTL distinction (driven by `engaged_at`) only decides *how soon* among already-
unclaimed accounts, so even a wrong engagement read can never delete a claimed account. This is the
deliberate "safety over cleanliness" choice — we keep an explicit lifecycle marker rather than
deriving "deletable?" from a live `count(auth_identities) = 0` join.

### What counts as "genuine" engagement

Engagement must be something the **tutorial did not ask the user to do** — otherwise simply
following the tutorial (e.g. completing a "Mark your first task done" item) would look like real
activity and wrongly promote every visitor to the long TTL.

Definition: **creating a task that is not tutorial-seeded** (or starting a real planning session).
Playing around with the tutorial tasks — completing, reordering, etc. — signals interest but not
customer value; there's nothing worth retaining in a shuffled list of tutorial tasks. What makes the
cut is the intent to use the board for its purpose: creating your own task. `engaged_at` is stamped
once, the first time this happens, and never cleared.

Tutorial tasks are also presented as **immutable** (see below), so the normal user can't rename a
tutorial task into a real one and dodge the definition. (It's a UX guardrail, not enforced server-
side — a determined user could bypass it, but there's no value at stake if they do.)

## Tutorial instead of sample data

`DemoDataSeeder`'s sample backlog ("Prepare weekly team update", a pre-built plan, etc.) made sense
for a showroom account. For an account meant to *become* the user's own, pre-loaded fake tasks are
clutter the user has to clean up. Replace the sample seed with a small **tutorial backlog** — real
tasks whose content teaches the product by having the user use it:

- "Welcome to Backlog.fyi — mark this task done to get started"
- "Add your own task (try the + button)"
- "Add an email or Telegram so your tasks are saved"
- "Set your assistant preferences"
- "Clear these tutorial tasks when you're ready"

(Final copy TBD; keep emoji to a minimum. Web UI is English-only, consistent with the rest of the
web surface.)

### Tutorial tasks are marked

Add a `tutorial` boolean to the task entity/table. It serves three purposes:

1. **One-click clear** — the "Clear tutorial" button deletes `where tutorial = true`. (A user who
   prefers can also just complete them one by one.) Clearing is always **explicit** — we never
   auto-remove tutorial tasks, including on claim, to avoid surprising a user mid-tutorial.
2. **Engagement detection** — "created a task that isn't tutorial-seeded" = the engagement signal.
3. **Planner hygiene** — keep tutorial tasks out of LLM/planning context if an unclaimed user later
   links Telegram and plans before clearing them.

**Tutorial tasks are immutable.** They can be completed (the tutorial asks for it) and cleared, but
not edited — no title/description/category/priority changes. This discourages the over-creative user
from repurposing a tutorial task as a real one (which would both pollute the engagement signal and
leave un-cleared seed rows masquerading as real data). The only interactions offered are
complete/uncomplete and delete.

This is a **UX guardrail, not a trust boundary** — client-side enforcement (hide/disable edit on
`tutorial` tasks) is sufficient. No customer value is lost if a determined user bypasses it via the
API, so we don't add server-side rejection.

## Activity tracking (`last_active_at`)

We want an activity timestamp that does **not** depend on Spring Session internals (so the deletion
logic survives any future session change). Approach:

- A `HandlerInterceptor` (or filter) over authenticated `/api/**` requests records
  `userId → Instant.now()` in an in-memory map.
- Persist **lazily and throttled**: only write `users.last_active_at` when the stored value is
  older than ~1 h. A `@Scheduled` flush every few minutes drains the dirty set; flush on shutdown.
- Write volume is therefore ~1 row update per active user per hour — negligible. The map is bounded
  by the active-user count.

The app already polls `GET /api/v1/boards/{boardId}/tasks/has-changes` frequently, so even a mostly-
idle open tab keeps `last_active_at` fresh; the interceptor approach captures that without coupling
to that one endpoint.

**Documented fallback:** if the interceptor ever proves heavier than expected, read activity from
Spring Session via the `SessionRepository` abstraction (not the raw `SPRING_SESSION` table). If we
go that route, leave a note here so a future session-store change doesn't silently break cleanup.

## Cleanup job (replaces `DemoCleanupService`)

```
delete accounts where claimed = false AND (
    (engaged_at IS NULL  AND last_active_at < now - SHORT_TTL) OR
    (engaged_at IS NOT NULL AND last_active_at < now - LONG_TTL)
)
```

Finalized values: `SHORT_TTL = 2d` (created, no engagement), `LONG_TTL = 14d` (engaged, not yet
claimed) of **inactivity**. Reuse the existing per-account teardown
(`AccountService.deleteUserData(userId)` then delete the `users` row), same as today's demo cleanup.
Keep the hourly schedule.

Abuse note: the old 24 h hard TTL bounded junk-row accumulation. With an inactivity window, abandoned
unclaimed accounts (and their DEKs/board rows) linger up to `SHORT_TTL`. Confirm the account-creation
endpoint stays behind `RateLimitInterceptor`.

## Claiming

No new "convert" code path is needed — claiming **is** linking an identity, which already exists and
is already guarded (can't unlink your last login method). On the first successful identity
link / verified-email attach, set `claimed = true`. Hook this into the existing
`UserAuthService.loginOrRegister` / account-linking paths and `EmailVerificationService`. Tutorial
tasks are left in place (cleared only by explicit user action).

## Schema changes (additive)

New changeset `004-account-lifecycle.xml`:

- `users.claimed BOOLEAN NOT NULL DEFAULT false`
- `users.engaged_at TIMESTAMP NULL`
- `users.last_active_at TIMESTAMP NULL`
- `backlog_task.tutorial BOOLEAN NOT NULL DEFAULT false`

**Existing columns:** `is_demo` and `demo_expires_at` stop being written (new zero-reg accounts are
just `claimed = false`). Per the additive-only rule we **don't drop them now**; retire them later
alongside the other deferred column retirements in `docs/AUTH-DECOUPLING.md`. Existing identity-
bearing accounts must be backfilled `claimed = true` (one-time update in the changeset:
`claimed = true where exists an auth_identities row OR email_verified_at is not null`).

**Legacy demo rows in prod:** demo accounts are 24 h-ephemeral, so by deploy time most are already
gone; the changeset's backfill leaves any survivors as `claimed = false`, and the new cleanup job
sweeps them on the short TTL. No bespoke migration needed.

## Metrics

Replace the `tasker.users.demo` gauge (`countByIsDemo`) with a cleaner conversion funnel:

- `tasker.users.unclaimed` — `claimed = false`
- `tasker.users.engaged_unclaimed` — `claimed = false AND engaged_at IS NOT NULL`
- (claimed total is derivable from the existing users gauge)

This gives a real "anonymous → engaged → claimed" funnel, which the old boolean couldn't.

## Frontend changes

- Keep the one-click start (currently "play in a sandbox →" / `onSandbox` → `demoLogin`). Rename
  copy away from "sandbox/demo" toward "start now" framing; the endpoint behavior is unchanged
  apart from no longer being throwaway.
- Add a persistent, dismissible **"Add an email or Telegram to keep your tasks"** banner shown while
  `claimed = false`. `MeResponse` needs to expose `claimed` for this.
- Add the **"Clear tutorial"** affordance on the board (only meaningful while tutorial tasks exist).
- Drop the 24 h session cap in `DemoAuthController` — unclaimed accounts get the normal 30-day
  rolling session.

## Open questions / follow-ups

- Final tutorial copy (kept minimal / low-emoji).
- Should an unclaimed account be allowed to use the planner at all before claiming, given it's
  channel-less? (Out of scope here; planner is still `userId`-bridged per BOARD-SHARING-PHASE1.)
- Rename `DemoAuthController` / `DemoDataSeeder` / `createDemoUser` to neutral names
  (`AnonymousAuthController`, `TutorialSeeder`, `createUnclaimedUser`) as part of the change.

Resolved: engagement = creating a non-tutorial task (tutorial tasks are immutable); TTLs finalized
at 2d / 14d; tutorial clearing is explicit-only.

## Affected code (today)

- `service/DemoCleanupService.kt` → rewrite as the lifecycle sweep.
- `service/DemoDataSeeder.kt` → tutorial seeder, sets `tutorial = true`.
- `service/UserAuthService.kt` → `createDemoUser` becomes `createUnclaimedUser` (no `is_demo`,
  no `demo_expires_at`); set `claimed = true` on first identity link / email verify.
- `controller/DemoAuthController.kt` → drop 24 h session cap; rename.
- `repository/UserRepository.kt` → replace `findExpiredDemoUsers` / `countByIsDemo` with lifecycle
  queries.
- `metrics/UsageMetrics.kt` → replace demo gauge with funnel gauges.
- `jpa/UserEntity.kt`, `jpa/BacklogTaskEntity.kt` → new fields.
- New `HandlerInterceptor` for `last_active_at`; wire in `WebConfiguration.kt`.
- `model/response/MeResponse.kt` + frontend `AuthContext` / banner / board.
</content>
