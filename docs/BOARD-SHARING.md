# Multi-board & sharing — plan

Status: **Phase 0 shipped to production; Phase 1 in progress (`docs/BOARD-SHARING-PHASE1.md`); Phase 2 planned (`docs/BOARD-SHARING-PHASE2.md`).**
This is the source of truth for the design and the phased work. It supersedes the
"Multi-board and sharing support" line in `docs/IDEAS.md` and
extends the non-goal "Shared tasks or collaboration features" in `docs/SPEC.md` (that non-goal is
being deliberately revisited — see [Goal](#goal)).

## Goal

Backlog.fyi is a *personal* task manager. We are **not** building Jira. But two real needs keep
coming up that a single per-user list can't serve:

1. **Shared household tasks** (primary). A family/home keeps a common list — anyone can add a
   task, pick one up, or mark it done — *while each member also keeps their own private list*. The
   private list must not become collateral of the shared one.
2. **Clean project/subject separation** (secondary). Some users want a hard wall between contexts
   rather than leaning on categories/tags. Lower priority — categories and tags already cover most
   of this.

### The key reframing: these are one feature

If we introduce a **board** as the thing that *owns* tasks (instead of the user owning them
directly), both needs fall out of one primitive:

- **Multi-board** = a user who belongs to several boards they're the only member of → project
  separation (need 2).
- **Sharing** = a board that simply has **more than one member** → household tasks (need 1).

"Privacy" stops being a feature you might forget to set and becomes an emergent property:
*a board is private iff it has exactly one member.* There is no separate "personal vs shared"
board type. The default board created at registration is an ordinary board like any other.

### Decisions locked for this plan

These were decided with the product owner up front:

- **One primitive: the board.** No per-task sharing (rejected — it turns authorization into a
  per-row ACL and the "who can see this one?" UX is exactly the awkwardness we want to avoid). No
  per-category/per-tag sharing (rejected — it splits "who owns the task" from "who owns its
  category/tag", which is per-task ACLs in disguise, and collides with the category cap).
- **Any board can be shared**, including the default one. We do **not** make the first board
  sacrosanct — that was considered and rejected as confusing, and privacy doesn't matter for every
  use case. Instead the *act* of sharing is gated behind a clear, explanatory consent dialog so it
  is always a deliberate, informed step.
- **Per-board encryption.** Shared task content cannot be encrypted under one member's key (see
  [What we found](#what-we-found-current-coupling)). Board-owned encrypted content moves to a
  **per-board DEK**; per-user encryption stays for personal profile/settings.
- **A lightweight claim/assignee** on tasks — the minimum needed so members don't double-schedule
  the same shared task. This is *not* per-task sharing; it's a single nullable owner field.
- **Planning stays personal.** The backlog is shared; the weekly planning conversation, calendar,
  and scheduling are per-user. The assistant is *told* which board each task belongs to and the
  user expresses any cross-board preference in their own words via the **context block** — we do
  **not** add per-board planning configuration in v1.
- **Migration: reset + reseed prod**, bridged by the existing **export/import** flow (extended to
  `formatVersion: 2`). Per `CLAUDE.md`, a reseed is acceptable at this user count; the export
  bridge means beta users don't lose data. We do **not** write an in-place re-encryption migration.

## What we found (current coupling)

Today **there is no board concept at all — the user *is* the board.** This is the central thing
the feature changes, and it's why it's a large change rather than an additive one.

### A. Ownership is the user id, everywhere

Every content entity carries a `user_id` and every query is scoped by it:

- `jpa/BacklogTask.kt`, `BacklogTaskCategoryEntity.kt`, `BacklogTaskTagEntity.kt` — all have
  `user_id`.
- `repository/*` — the access pattern is uniformly `findByIdAndUserId`, `findAllByUserId…`. There
  is no other authorization layer; you simply can't fetch a row that isn't keyed to your id.
- `service/BacklogTaskService.kt` threads `userId: UUID` through every method and scopes both the
  task lookup *and* its category/tag resolution to that user.
- Categories and tags are **per-user** too (`resolveOrCreateTags` searches `findAllByUserId`;
  `UserService.DEFAULT_CATEGORIES` are seeded per user in `initializeNewUser`).

So authorization is *implicit in the user id*. The feature replaces "implicitly your own rows" with
"rows owned by a board you're a member of" — an explicit membership check on every access path.

### B. Encryption is per-user and AAD-bound to the user id (the real blocker)

`crypto/UserCryptoService.kt` does per-user envelope encryption: a random DEK per user, wrapped
under the app KEK, with **the user's UUID bytes bound as AAD**:

```kotlin
AesGcmCipher.seal(dekFor(userId), plaintext, userIdAad(userId))
```

A task written by user A **cannot be decrypted in user B's context** — different DEK, and the AAD
won't match even if you had the key. Sharing is therefore *impossible* without re-keying. What is
actually encrypted at the task level is narrow: **`backlog_task.title`, `backlog_task.description`,
and `backlog_task_change_event.task_title_snapshot`** (category/tag labels are plaintext). Personal
fields — `users.email`, `users.telegram_first_name`, and all of `user_settings`
(`display_name`, `context_block`, `agent_description`) — are also under the user DEK but are *not*
board data and should stay personal. So we need **both** keys, not a wholesale move (see design).

### C. The planner and change-tracking are per-user

- `planning/PlannerTaskSelector.select(userId, …)` pulls `findAllByUserIdAndStatus(userId, TODO)` —
  a single user's backlog — and never spans more than one list.
- `planning/BacklogTaskChangeService` records change events and a "tasks changed" **watermark**
  per `user_id`. The watermark backs the web board's polling refresh; the events back the planner's
  inter-session "what changed this week" diff. Both will need a board dimension so a change by one
  member surfaces to another.
- `planning_session` / `planned_task` / `planned_task_slot` are per `user_id` — these are the
  user's *personal* plan and calendar commitments, and should **stay per-user** (you plan your own
  week off a possibly-shared backlog).

### D. What we can reuse (the good news)

- **Export/import already exists for exactly this** (`controller/AccountController.kt`,
  `service/AccountService.exportAccount` + `AccountImportService`, spec in
  `docs/export-format-v1.md`). Its docstring literally describes "export on the old build, wipe the
  DB, re-auth on the new build, import" — built for the prior encryption migration. We extend the
  format to v2 rather than building a bridge from scratch.
- **Identity & invitation infra** (`docs/AUTH-DECOUPLING.md`): passwordless email magic-link
  registration/login (`POST /api/auth/email` + callback) and account-linking already resolve people
  by email/Telegram. Board invitations latch onto the magic-link flow — an invited non-user
  registers via the link and is dropped straight into the board.
- The envelope design in `UserCryptoService` generalizes cleanly: a board DEK is the same pattern
  with the board id as AAD.

## Target design

### 1. `board` and `board_membership`

```
board
  id          UUID  PK
  name        BYTEA      -- encrypted under the board DEK (see §3); members decrypt it
  created_at  TIMESTAMP  not null

board_membership
  id         UUID  PK
  board_id   UUID  FK -> board(id), not null, indexed
  user_id    UUID  FK -> users(id), not null, indexed
  role       VARCHAR    not null   -- 'OWNER' | 'MEMBER'
  joined_at  TIMESTAMP  not null
  -- unique (board_id, user_id)   -> a user appears at most once per board
```

- **Roles are deliberately minimal: `OWNER` and `MEMBER`.** Members have full CRUD on tasks (the
  household "anyone can add / pick up / complete"). Owner additionally renames the board, manages
  members, and deletes the board. No viewer/commenter tier, no per-task permissions — we add tiers
  later only if a real need appears.
- A board always has **≥1 OWNER**. Privacy is emergent: `count(board_membership) == 1` ⇒ private.
- `board.name` is encrypted under the board DEK so it's consistent with the rest of the privacy
  posture; every member shares the DEK so all can read it. (Low-stakes; flagged as a revisitable
  decision in [Open questions](#open-questions--risks).)

### 2. Content moves from user-owned to board-owned

Repoint the *owning* foreign key on shared content from `user_id` to `board_id`:

| Table | Change |
|---|---|
| `backlog_task` | `user_id` → `board_id`; **add** `assignee_user_id UUID null` (see §5) |
| `backlog_task_category` | `user_id` → `board_id` (categories belong to the board; the default set is seeded per board) |
| `backlog_task_tag` | `user_id` → `board_id` |
| `backlog_task_change_event` | `user_id` → `board_id` (a shared board's change feed is shared) |
| `backlog_task_watermark` | keyed by `board_id` (so a member's edit refreshes others' open tabs) |

Stays **user-owned** (personal plan/calendar, not backlog content):
`planning_session`, `planned_task`, `planned_task_slot`. A `planned_task` keeps its
`backlog_task_id` reference into a (possibly shared) board task, but the *plan* is yours.

Repositories change `findByIdAndUserId` → `findByIdAndBoardId`, paired with a membership check
(next section). `BacklogTaskService` methods take `boardId` and an acting `userId` (the latter only
for authorization, the `assignee`, and audit — not for row ownership).

### 3. Encryption: per-board DEK for board content, per-user DEK stays for personal data

We keep **two** envelope scopes:

- **Per-user DEK** (`user_data_key`, today's `UserCryptoService`) — unchanged. Still encrypts
  personal fields: `users.email`, `users.telegram_first_name`, and `user_settings.*`
  (`display_name`, `context_block`, `agent_description`).
- **Per-board DEK** (new `board_data_key` table; new `BoardCryptoService` mirroring the existing
  one, AAD = board id). Encrypts board-owned content: `backlog_task.title`/`description`,
  `backlog_task_change_event.task_title_snapshot`, and `board.name`.

`board_data_key` is created when a board is created (mirrors `ensureUserKey`, wrapped under the same
`TASKER_DATA_KEK`). This also means **board content survives any individual member leaving** — the
key belongs to the board, not a person — which is exactly what makes account deletion tractable (§6).

> Correction to an earlier sketch: this is *not* a wholesale move of all encryption to the board.
> Only the genuinely shared, board-owned ciphertext re-keys; personal profile/settings stay under
> the user DEK.

### 4. Authorization: membership replaces user-identity

A central guard replaces the implicit user-id scoping:

```
BoardMembershipService.requireMember(userId, boardId): Role   // throws 403/404 otherwise
```

Every board-scoped controller resolves the board from the request, calls `requireMember`, then
operates by `board_id`. **Decided at Phase 1 start: path-scoped** —
`/api/v1/boards/{boardId}/tasks`. Explicit, every request names its board, easy to guard
centrally; the alternative (active-board in the session) was rejected for its hidden state and
awkward multi-tab behavior. Non-member access uniformly returns 403 without revealing whether the
board exists.

Owner-only actions (rename, manage members, delete board) additionally assert `role == OWNER`.

### 5. Claim / assignee — the one new cross-member primitive

`backlog_task.assignee_user_id` (nullable):

- `null` = **unassigned / up for grabs**.
- set = **claimed** by that member; visible to all members in the UI (avatar/chip) and to each
  member's planner.
- On a single-member board it's effectively always you-or-null; it only earns its keep when shared.

It is set by an explicit **"claim / assign to me"** action *and* automatically when a member's
planner schedules the task into their week (alongside the existing `last_scheduled_in_session_id`).
This is the minimum coordination needed so two members don't both block time for "buy groceries".

### 6. Account deletion of a board member

`AccountService.deleteUserData` today deletes everything by `user_id`. New, board-aware logic — for
each board the departing user belongs to:

- **Sole member** → delete the board and all its content (tasks, join rows, categories, tags,
  change events, watermark, `board_data_key`).
- **OWNER with other members remaining** → **transfer ownership** before leaving. Default:
  auto-promote the **longest-tenured** remaining member to `OWNER` (least-surprising, no
  interaction needed). Then remove the departing membership; board content stays.
- **MEMBER (non-owner)** → just remove the membership row. The board and its content — including
  tasks this user authored — survive for the others (shared data, like a shared doc).

Then delete user-scoped data as today: `user_settings`, `user_data_key`, `auth_identities`,
sessions, `ai_conversation`, and the user's `planning_session`/`planned_task`/`planned_task_slot`
(their personal plans). A `planned_task.backlog_task_id` that pointed at a surviving shared task
simply goes with the user's plan.

> Privacy nuance to confirm in copy: a departing member's *authored* tasks persist on a shared
> board. That's the correct semantics for shared data, but the deletion UI should say so plainly.

### 7. The assistant with multiple boards

Holding to "the backlog is shared, planning is personal":

- **Candidate selection spans boards.** `PlannerTaskSelector` changes from
  `findAllByUserIdAndStatus(userId, TODO)` to the union of TODO tasks across **every board the user
  is a member of**, **excluding tasks claimed by *other* members** (include unassigned + assigned-to-me).
- **Each candidate is annotated with its board** in the prompt, so the model knows
  "Buy nappies (Home)" vs "Ship feature (Work)".
- **Cross-board preferences live in the context block**, in the user's own words
  ("Home chores only on weekends", "don't plan Side-project tasks during work hours"). We do **not**
  add per-board planning config, weights, or a per-board agent persona in v1 — the planner persona
  and prefs stay coherent and personal.
- On scheduling, stamp `assignee_user_id = me` so other members' planners stop offering the task.
- Change-tracking (`summarizeSince`) aggregates across the user's boards; title snapshots re-key to
  the owning board's DEK.

Open tuning questions (Phase 2): per-board candidate caps so a busy board can't crowd out a quiet
one; whether the planner ever surfaces a task already claimed by someone else (default: no).

### 8. Invitation & consent flow (initial)

Deliberately simple for v1, latching onto existing email auth:

1. Owner opens the board's **Members** panel → **Invite**, types the invitee's **email**, and must
   confirm a **consent dialog** that spells out the consequences in plain language: *everyone you
   add can see and edit every task on this board, including ones already there, until you remove
   them.* This dialog is the privacy guardrail — sharing is never a quiet toggle.
2. Server creates a `board_invitation` (board_id, email_hash, single-use token, expiry, invited_by,
   status) and sends a localized invite email via the **auth** sender.
3. Invitee clicks the link:
   - **Not a user** → magic-link registration (existing flow) → on landing, auto-accept → membership
     row created.
   - **Existing user** → a small accept screen → membership row created.
4. Token is single-use, short-TTL, rate-limited — same hardening as `email_login_token`.

`board_invitation` mirrors the `email_login_token` design (email kept as `email_hash`; no plaintext
needed since the address came from the inviter). Refusing/expiring and re-inviting are owner actions.

### 9. Export format v2 (migration bridge)

The prod reset is bridged by extending the existing export/import to `formatVersion: 2` — v1
reshaped to nest `categories`/`tags`/`tasks` under a `boards[]` array. **Crucially, the Phase-0
reset happens *before* multi-board/sharing ship**, so every account exports exactly **one** board;
v2 never has to represent multiple or shared boards. The export side ships on a **separate,
prod-safe branch** (it only reshapes read-only JSON — no schema change), decoupled from this
heavily-tested refactor branch.

**The full format spec and export/import behavior live in `docs/export-format-v2.md`.**

### 10. Frontend

- **Board switcher** in the app shell (think Slack workspaces). The active board scopes the task
  list, filters, categories, and tags. Start with **one active board at a time** + switcher; a
  unified "all boards" view is deferred (it multiplies query/UX complexity: merged categories,
  cross-board sort).
- **Board CRUD** (create/rename/delete) and a **Members** panel (list, invite, leave, owner-only
  remove/transfer).
- **Invite + consent dialog** (§8).
- **Assignee chip / "claim" affordance** on shared-board tasks.
- New top-level routes get registered in `controller/SpaForwardController.kt` (per `CLAUDE.md`).
- The shell already degrades for channel-less users; board-awareness is additive.

## Phasing

Designed so the scary, pervasive refactor lands **first and invisibly**, and user-visible sharing
is incremental.

1. **Phase 0 — invisible foundation (no behavior change).** Add `board`, `board_membership`,
   `board_data_key`; give every user exactly one board (their existing content); add the
   `BoardCryptoService` and re-key task content to the board DEK; repoint all
   task/category/tag/change-event/watermark access from `user_id` to `board_id` behind a membership
   check; extend export to v2. Ships looking **identical** to today (one implicit board each).
   Delivered via **reset + reseed**, with the export→import bridge so beta users keep data.
   **Concrete implementation plan: `docs/BOARD-SHARING-PHASE0.md`. Export/import bridge spec (separate
   prod-safe branch): `docs/export-format-v2.md`.**
2. **Phase 1 — multi-board, single user.** Board CRUD + switcher UI; planner spans the user's
   boards (§7); finalize the board-in-request convention (§4). No sharing yet. Delivers need 2.
   **Concrete implementation plan (three PRs): `docs/BOARD-SHARING-PHASE1.md`.**
3. **Phase 2 — sharing.** Invitations + consent dialog (§8), `MEMBER`/`OWNER` enforcement,
   claim/assignee UI (§5), Members panel, ownership-transfer-on-delete (§6). Delivers need 1.
   **Concrete implementation plan (three PRs): `docs/BOARD-SHARING-PHASE2.md`.**

## Open questions / risks

- ~~**Board-in-request convention**~~ — resolved: path-scoped (§4, `BOARD-SHARING-PHASE1.md`).
- **Planner fairness across boards** — fixed urgent/stale slots over a merged pool may let a busy
  board starve a quiet one. May need per-board caps or interleaving. Tune in Phase 2.
- **`board.name` encryption** — encrypting under the board DEK is consistent but adds a decrypt on
  every switcher render; if it bites, names are arguably low-sensitivity and could go plaintext.
- **Concurrent edits** by two members on one task — last-write-wins via `updated_at` for v1; flag
  if it causes surprises.
- **Departed-member content** persists on shared boards (correct for shared data) — ensure the
  account-deletion copy is explicit so it isn't a surprise.
- **Category cap** becomes per-board (was per-user) — confirm that's the intended semantics.
- **Demo users** get a board too; demo cleanup (`DemoCleanupService`) must delete the demo board
  and its `board_data_key` alongside the user.
- **Performance** — a membership join/lookup now sits on every content access; index
  `board_membership(user_id)` and `(board_id, user_id)`.
- **Shared-board export round-trip** post-Phase-2 (coordinating who recreates a shared board) —
  deferred; not needed for the Phase-0 migration.
