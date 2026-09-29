# Decoupling auth from Telegram — plan

Status: **Phases 1–4 implemented** (identity layer, email magic-link login, channel-less
hardening, account linking — see the per-phase ✅ notes under [Phasing](#phasing)). **Phase 5
deferred**: Google OAuth login (tracked as a GitHub issue). **Phase 6 dropped**: retiring the
`telegram_id` / `email_hash` columns. On investigation they are not superseded lookup keys but
profile state, so they stay.

## Goal

Today Telegram is the *only* way to register, and it is woven through the app as both an
**identity** and a **communication channel**. We want to decouple these so that:

- Telegram is no longer *required* to register. It keeps a central role, but becomes one
  login method among several.
- Users can register with **email** (passwordless magic link) now, and additional methods
  (Google OAuth, etc.) can be added later without schema churn.
- The app behaves correctly for users with **no communication channel at all** (e.g. Google
  OAuth without email sharing). Such a user can still use AI features through the web UI
  planning drawer; they simply have no push/scheduled channel.

Decisions locked for this plan:

- **Identity model:** a separate `auth_identities` table (one user → many provider identities).
- **Email login:** passwordless **magic link**, reusing the existing email-verification + SMTP
  machinery.
- We are in limited beta and may make **breaking schema changes**, including wiping/reseeding
  prod (per `CLAUDE.md`).

## What we found (current coupling)

The good news: the session layer is already decoupled. `TaskerPrincipal` carries only a
`userId: UUID` (`security/TaskerPrincipal.kt`), `SessionAuthenticator.authenticate` takes any
principal, and `telegram_id` is already a nullable, unique column. The **demo-login** flow
(`controller/DemoAuthController.kt` → `UserAuthService.createDemoUser`) already mints fully
functional users with **no** `telegram_id`, which proves the rest of the app tolerates
channel-less identities. The coupling that remains is concentrated in two areas.

### A. Identity / registration (the real blocker)

- `controller/AuthController.kt` exposes a single login endpoint, `POST /api/auth/telegram`.
- `service/UserAuthService.kt`:
  - `loginOrRegisterByTelegram` is the only real registration path; every new user is created
    with a `telegramId` set (lines 37–48).
  - `ensureDevUser(userId, telegramId)` also requires a telegram id; dev passes the sentinel
    `DEV_USER_TELEGRAM_ID = 0L` (`config/DevAuthConstants.kt`).
- `repository/UserRepository.kt`: `findByTelegramId` is the only identity-based lookup used in
  auth. (`findByEmailVerificationToken` already exists for the verify-email flow.)
- Identity columns live directly on `users` (`jpa/UserEntity.kt`): `telegram_id` (unique),
  plus `email` / `email_hash` (unique) / `email_verified_at` / `email_verification_token`.

### B. Communication / scheduling channel

- `planning/PlanningSessionScheduler.kt` (lines 84–96): scheduled weekly planning is
  hard-wired to Telegram — it reads `user.telegramId` as the chat id and **silently returns**
  if it is null. So a channel-less or email-only user with a `planningCron` set gets nothing.
- `channel/telegram/TelegramChannel.kt`: inbound Telegram messages are routed by
  `findByTelegramId`; unknown senders get a "Please sign up at backlog.fyi" reply. This is fine
  to keep — it is the Telegram *front door*, not a general assumption.
- `channel/telegram/commands/*` (`/plan`, `/current`): Telegram-only on-demand entry points.
- The **web planning drawer** already works with **no** channel assumption:
  `controller/WebPlanningController.kt` drives `WeeklyPlanningOrchestrator` through a
  `BufferedConversationChannel` and never touches `telegramId`. This is the path channel-less
  users rely on, and it already exists.
- **Notifications/delivery** are already abstracted behind an *eligibility* idea:
  `planning/InviteDeliveryResolver.kt` resolves whether a user can receive calendar invites
  (email enabled + verified + setting on) and `describeDeliveryMethods` tells the planner, in
  plain language, when a user has **"NO active reminder/notification delivery method"**. This
  is the precedent we extend: "no channel" is already a first-class, handled state for delivery
  — we make it first-class for *registration and scheduling* too.

### Channel abstraction (already present)

- `channel/ConversationChannel.kt` — bidirectional (Telegram, buffered-web).
- `channel/OutboundChannel.kt` — one-way push (SMTP email, logging fallback).

The interfaces exist; what is missing is a **selector** that picks a conversation channel for a
user, and a graceful "this user has no conversation channel" answer for scheduling.

## Target design

### 1. `auth_identities` table

A user has zero-or-more identities, each from a provider. Login = look up the identity, resolve
to its `user_id`, create the session. Registration = create a user (if the identity is new) and
attach the identity. Linking a second method later = attach another row to the same `user_id`.

```
auth_identities
  id                UUID  PK
  user_id           UUID  FK -> users(id), not null, indexed
  provider          VARCHAR  not null   -- 'telegram' | 'email' | 'google' | ...
  provider_user_id  VARCHAR  not null   -- telegram id as text / email_hash / google 'sub'
  verified_at       TIMESTAMP null      -- when ownership was proven (e.g. magic-link click)
  created_at        TIMESTAMP not null
  last_login_at     TIMESTAMP null
  -- unique (provider, provider_user_id)  -> one external identity maps to one user
```

Notes:
- `provider_user_id` is a **non-secret stable handle**. For Telegram it is the numeric id; for
  email it is the **`email_hash`** (SHA-256 of normalized email — same scheme already used on
  `users.email_hash`), never the plaintext. Plaintext email stays encrypted on `users.email`.
- Profile/display data (telegram username/first-name/photo, the encrypted email) **stays on
  `users`** — those are user attributes, not identity keys. We are only moving the *lookup keys*
  into `auth_identities`.
- A user with rows only for providers that carry no channel (e.g. just `google`) is the
  channel-less case and is fully valid.

#### Migration (changeset `005-auth-identities.xml`, additive + backfill)

Because we are additive-only against a real Postgres (`CLAUDE.md`), this is a *new* changeset,
not an edit of `001-schema.xml`:

1. `createTable auth_identities` with the unique constraint above.
2. Backfill: for every `users` row with `telegram_id`, insert a `('telegram', telegram_id)`
   identity; for every row with a non-null `email_hash` **and** `email_verified_at`, insert a
   verified `('email', email_hash)` identity. (SQL `INSERT ... SELECT`, runs in the changeset.)
3. Leave `users.telegram_id` / `email_hash` columns in place for now (read path moves to the
   new table; we can drop the unique constraints / columns in a later changeset once nothing
   reads them — or, given beta, fold it into a reseed). **Do not** drop in the same changeset.

> Alternative we explicitly considered and rejected: keeping per-provider nullable columns on
> `users`. It makes account-linking and the 4th provider awkward and keeps lookups per-column.
> The user chose the `auth_identities` table for exactly this forward-looking reason.

### 2. Identity-centric service layer

Introduce a thin abstraction so each provider plugs in the same way:

- `AuthIdentityEntity` + `AuthIdentityRepository`
  - `findByProviderAndProviderUserId(provider, providerUserId): AuthIdentityEntity?`
  - `findAllByUserId(userId): List<AuthIdentityEntity>`
- `UserAuthService` (refactor):
  - Generalize the current Telegram-specific logic into:
    `loginOrRegister(provider, providerUserId, verified, profileUpdater): UserEntity`
    1. look up identity → if found, touch `last_login_at`, run `profileUpdater`, return its user;
    2. else create a `UserEntity` (the existing DEK / `initializeNewUser` dance from
       `loginOrRegisterByTelegram` lines 36–49, factored out), attach the identity, return.
  - `loginOrRegisterByTelegram` becomes a thin caller of the above (`provider='telegram'`,
    `providerUserId = telegramId.toString()`, profileUpdater sets username/first-name/photo).
  - Dev/demo users get explicit identities too (or none, for demo) instead of the `0L` sentinel.

This keeps the careful ordering that exists today (persist user row before `ensureUserKey` so
the `user_data_key` FK is satisfied) — see `UserAuthService.kt:34–49`.

### 3. Email magic-link login

Reuse the existing verification infra (`EmailVerificationService`, the `email_verification_token`
+ `*_expires_at` columns, SMTP `OutboundChannel`). Flow:

1. `POST /api/auth/email/request { email }` (unauthenticated, CSRF-exempt like the other login
   endpoints per `CLAUDE.md`): normalize + hash the email, generate a single-use, short-TTL
   token, store it (keyed so we can resolve it back to the email on click), and send a localized
   "click to sign in" email. **Always return 200** regardless of whether the address is known —
   no account enumeration.
2. `GET /api/auth/email/callback?token=…` (or a `POST` from a tiny SPA page): validate the token
   (exists, unexpired, unused), then `loginOrRegister('email', emailHash, verified=true, …)`,
   storing the encrypted plaintext email + `email_verified_at` on the user, mark token used,
   create the session via `SessionAuthenticator`, redirect into the app.

Design points:
- This is **both** registration and login — first click creates the account, later clicks log in.
- A verified email identity automatically makes the user eligible for calendar invites via the
  existing `InviteDeliveryResolver` (verified email + setting). Nice reuse, no new delivery code.
- Reuse / refactor the existing settings-email verification rather than duplicating token logic;
  consider unifying both under one token service.
- Token must be single-use and rate-limited per email/IP to prevent abuse of the send endpoint.

#### Collision rules (decided)

When a magic-link login resolves an email that already relates to an existing account, the
callback must decide whether to log into that account, create a new one, or refuse. Rules:

| Situation | Behavior |
|-----------|-----------|
| Email matches an existing user with that email **verified** | Log into that existing user (attach an `email` identity if one isn't already present). |
| Email matches an existing user with that email **unverified** | **Fail** the login: ask the user to sign in with Telegram and verify the email in settings, and show a support/help email for "I don't recognize this account". This is a rare edge case — keep it cheap, do not auto-merge. |
| Email belongs to no user | Create a new account with an `email` identity (the normal registration path). |
| User has a Telegram account with **no** email, then signs in by email | Two separate accounts result. If they later authenticate with the *other* method from within one of these accounts, **offer account linking**; if they decline, disallow the cross-login. (Linking itself is Phase 4 — see below.) |

The matching key is `email_hash`, so we never compare plaintext. "Verified" is decided by the
existing `users.email_verified_at` (equivalently a verified `email` identity once backfilled).

### 4. Channel-less correctness (scheduling + planner expectations)

- **Scheduler:** generalize `PlanningSessionScheduler.runPlanningSession` to ask a new
  `ConversationChannelResolver` for the user's scheduled channel. If the user has a Telegram
  identity → `TelegramConversationChannel` (today's behavior). If not → there is no push channel,
  so a scheduled run cannot *deliver* a conversation. Options, in order of preference:
  - **Preferred for v1:** only register a cron for users who actually have a deliverable channel
    (gate `scheduleFor` on "has a conversation channel"), and surface scheduling in the web UI as
    unavailable/"needs Telegram or email" for channel-less users. This avoids firing cron jobs
    that can only no-op.
  - Later: an in-app/"next time you open the drawer" nudge or an email-initiated planning link
    for email-only users.
- **Planner prompt:** `InviteDeliveryResolver.describeDeliveryMethods` already tells the model
  when there is no reminder channel — keep using it so the assistant never promises a
  notification that will not arrive. No change needed beyond making sure email-verified users
  flip it to the "you'll get a calendar invite" branch (they already do).
- **Audit for hard `telegramId!!` / non-null assumptions** before shipping: the known ones are
  `PlanningSessionScheduler` (handled above) and `TelegramChannel` (front-door, fine). Grep for
  `telegramId` across `planning/`, `channel/`, and any notification code as part of the work.

### 5. Frontend

The redesigned welcome page (`auth/LoginPage.tsx`) already has Telegram wired and **Google /
email buttons stubbed with a "Soon" badge**. For this milestone:

- Wire the **email** button to a small inline form → `POST /api/auth/email/request` → "check your
  inbox" confirmation. Leave Google stubbed (out of scope).
- Handle the magic-link landing route (a new top-level SPA route, e.g. `/auth/email`), which
  means adding it to `controller/SpaForwardController.kt` (per `CLAUDE.md`'s routing note).
- `AuthUser` (`auth/authApi.ts`) currently exposes telegram\* + email. Once a user can have *no*
  telegram and *no* email, make sure the shell/header degrades gracefully (display name fallback,
  no "telegram first name" assumption). The planning drawer already works channel-agnostically.

## Phasing

1. **Schema + identity layer (no behavior change) — ✅ implemented in this PR.** Added the
   `auth_identities` table + Postgres backfill (`005-auth-identities.xml`), `AuthIdentityEntity`
   / `AuthIdentityRepository`, and refactored `UserAuthService` to a generalized
   `loginOrRegister(provider, providerUserId, verified, onExisting, onCreate)` with Telegram as
   the first caller. Telegram login keeps working byte-for-byte; demo users stay channel-less
   (no identity); the dev user gets a Telegram identity so a dev Telegram login can't collide on
   `users.telegram_id`.
2. **Email magic-link login — ✅ implemented in this PR.** `POST /api/auth/email` sends a
   single-use, 30-min, rate-limited magic link (always 204, no account enumeration); the pending
   email is held in a new `email_login_token` table encrypted under the app KEK (no user DEK
   exists yet — see `UserCryptoService.encryptSystem`). `GET /api/auth/email/callback` consumes
   the token, applies the collision rules via `UserAuthService.loginByEmail`, creates the session,
   and redirects into the app (`/`, or `/?emailLogin=unverified|invalid` on the failure branches,
   surfaced as a notice on the login page). The welcome-page email button is wired; the callback
   is a server redirect, so no new SPA route was needed. Two add-ons landed alongside:
   - **Email config split** into two independent senders (`tasker.email.auth.*`,
     `tasker.email.scheduling.*`), each with its own SMTP account, so a scheduling-mailbox issue
     can't break login email. `EmailVerificationService`/`EmailLoginService` use the `auth`
     channel; `CalendarInvitationComposer` uses `scheduling`. (Breaking env-var change — see CLAUDE.md.)
   - **Delivery metric** `tasker.email.sent{purpose,outcome}` via an `EmailMetricsOutboundChannel`
     decorator, matching the existing `AiUsageTracker` Prometheus pattern; ready to alert in Grafana.

   Still gated by `TASKER_EMAIL_ENABLED` (disabled → both senders log the link instead of sending).
3. **Channel-less hardening — ✅ implemented in this PR.** Extracted
   `ScheduledConversationChannelResolver`, which owns the "scheduled planning needs a push
   channel (Telegram today)" assumption and returns `null` for channel-less users.
   `PlanningSessionScheduler` now (a) gates cron registration on `hasDeliverableChannel` — a
   channel-less user with a `planningCron` is skipped instead of scheduled into a no-op — and
   (b) resolves the channel at fire time, recording the started session via the resolver's hook
   rather than touching `telegramId`/`sessionRegistry` directly. Audit of `telegramId` in
   `planning/`+`channel/` confirms the only remaining use is the legitimate Telegram inbound
   front door (`TelegramChannel`). The frontend already degrades for channel-less users
   (`UserMenu` falls back display name → telegram → email → "Account"), so no FE change needed.
   > Follow-up for Phase 4: linking a push channel to an existing account should re-publish
   > `UserPlanningScheduleChangedEvent` so a previously-skipped cron gets registered.
4. **Account linking — ✅ implemented in this PR.** A logged-in user can attach more login
   methods (a "Connected accounts" section in Settings):
   - **Link Telegram** (`POST /api/auth/link/telegram`) — synchronous; the widget HMAC proves
     ownership. On success it sets the Telegram profile, attaches the identity, and re-publishes
     `UserPlanningScheduleChangedEvent` (the Phase-3 follow-up: a previously-skipped cron now
     registers because a push channel exists).
   - **Link email** — handled by making the existing settings email-verify flow create an email
     identity on confirmation, so "verify your email" == "email is now a login method". The
     verify request also refuses an address already owned by another account (409).
   - **List** (`GET /api/auth/identities`) and **unlink**
     (`DELETE /api/auth/identities/{provider}`), guarded so you can't remove your last login
     method; unlinking Telegram re-publishes the reschedule event (channel gone → cron skipped).
   - **Conflict policy (decided):** linking an identity owned by a *different* account is refused
     with guidance (409) — no data-bearing account merge. Merging two populated accounts remains
     out of scope.
   - Provider-agnostic: a future Google method is one more `link/google` caller + a row in the
     same screen, no new flow. `AccountLinkService`/`AccountLinkController` are the seam.
5. **(Later, out of scope)** Google OAuth as a provider — drops in as another
   `loginOrRegister('google', sub, …)` caller + identity rows, no schema change.
6. **(Dropped)** ~~Retire `users.telegram_id` / `email_hash`.~~ Investigated later: they hold
   profile state and are not superseded by `auth_identities`, so they are kept.

## Open questions / risks

- **Account linking UX:** the collision rules above are decided (verified-email match logs in;
  unverified-email match fails with guidance; otherwise create). Linking two *existing* accounts
  is deferred to Phase 4 and is mostly UI/UX work. Open sub-question: when offering "is this you?
  link them", how do we re-prove ownership of the *other* account before merging — re-auth with
  that provider in the same session is the likely answer.
- **Email deliverability** is now on the critical *login* path, not just notifications — a
  bounced/delayed magic-link mail means a user cannot get in. Monitor send failures; keep
  Telegram as the always-available fallback during beta.
- **Token security:** single-use, short TTL, constant-time compare, rate-limited send, no
  enumeration. The settings-email verification flow should be unified with this so there is one
  hardened token path.
- **Encryption:** `email` is encrypted at rest under the user DEK; `email_hash` is the only
  thing stored in `auth_identities`. Confirm no new field needs encryption when we add it
  (per `CLAUDE.md` privacy rule).
