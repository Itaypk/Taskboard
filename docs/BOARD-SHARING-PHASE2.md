# Board sharing — Phase 2 (sharing)

Status: **Implementation plan — not started.** Phase 2 of `docs/BOARD-SHARING.md`. Phases 0–1 are
done: boards own all content, the API is path-scoped (`/api/v1/boards/{boardId}/…`), users can have
several boards, and the planner spans them. Every board still has exactly one member. Phase 2 makes
boards *shareable*: invitations + consent, a Members panel, the claim/assignee primitive, a
board-keyed change feed, and ownership transfer on account deletion. Delivers need 1 (shared
household tasks).

Unlike Phase 0, there is **no prod reset** planned for this phase. Production carries real data, so
every schema change here must be an additive changeset that works on the live DB (the one deliberate
data-drop is called out and justified in PR 1).

## Phase 1 retrospective — one gap to settle before we start

Reviewing Phase 0/1 for flaws, nothing was found that requires reopening them. The conscious
deviations (per-user watermark/change events, the spurious cross-board `has-changes` trigger) are
exactly what this phase corrects. But one wrinkle was **not** spelled out when the re-key was
deferred:

> `backlog_task_change_event.task_title_snapshot` is encrypted under the **user DEK**
> (`BacklogTaskChangeService` lines ~32/51/64). Re-keying the table to the board therefore cannot
> be a SQL backfill — ciphertext under the user key is useless in a board context. The options are
> (a) a code-level decrypt/re-encrypt migration, or (b) dropping the existing rows.

**Decision: (b) — drop the rows.** Change events and the watermark are ephemeral signal data: the
watermark rebuilds on the next edit, and losing the events costs each user exactly one "since your
last session" diff in their next planning conversation (the planner degrades gracefully when the
feed is empty — same as a first session). A throwaway re-encryption migration for that is not worth
writing or testing. This is a Phase-2 decision, not a Phase-1 revision — no Phase-1 rework needed.

## Decisions settled at Phase 2 start

These resolve the items `BOARD-SHARING.md` and the Phase 0/1 docs deferred to "Phase 2", plus the
product calls sharing forces:

1. **Change feed re-key = drop & repoint** (see retrospective above). New changeset deletes all
   `backlog_task_change_event` / `backlog_task_watermark` rows, repoints both to `board_id`, and
   snapshots are encrypted under the **board DEK** from then on — so a feed entry survives its
   author leaving the board.
2. **Change events keep an actor.** The existing `user_id` becomes `actor_user_id` (nullable) —
   scoping moves to `board_id`, but "who did it" is real information on a shared board ("Dana
   completed *Buy filters*"). Nullable because the actor's account may be deleted while the board
   survives; account deletion nulls it rather than deleting board history. `StatsService` keeps its
   personal semantics by querying events by actor (your stats = what *you* did, across your boards).
3. **Invitation = email capability token, no strict account match.** The invite flow mirrors
   `email_login_token` (single-use, TTL, rate-limited, `email_hash` only — no plaintext persisted),
   but the *acceptor* doesn't have to log in with the invited email: whoever presents the token may
   accept with whatever account they're signed into (the accept screen shows clearly which account
   that is). Rationale: household members may prefer Telegram login, and forcing email
   verification first kills the funnel. Trade-off (a forwarded email grants access) is acceptable
   because the consent dialog says exactly that, the token is single-use with a 7-day TTL, and the
   owner sees and can remove every member. Flagged deliberately — this is the capability-URL model.
4. **Member display names are resolved server-side, with masking.** Display data is encrypted under
   each user's own DEK, but that's an at-rest boundary, not an authorization one — the server holds
   the KEK and already decrypts in-request. The members endpoint returns, per member, the first
   available of: `user_settings.display_name` → `telegram_first_name` → `telegram_username` →
   **masked** email (`it***@gmail.com`) → "Member". We do **not** expose a co-member's full email.
   This is a deliberate, documented exception to "only decrypt a user's data in their own request
   context"; the consent/accept copy states that your name becomes visible to board members. No new
   column, names stay fresh. (Rejected alternative: a plaintext per-membership display-name column —
   stale copies and one more thing to edit.)
5. **Multi-owner is allowed.** The invariant stays "≥ 1 OWNER", not "exactly 1". Households want
   two owners, and *allowing* several is less code than enforcing one. Role management is
   `PATCH …/members/{userId}` (owner-only); the guard is "cannot demote/remove the last OWNER".
6. **Owner leave/deletion auto-promotes.** When the only owner leaves (or deletes their account)
   and other members remain, the longest-tenured member (`min(joined_at)`, tie-break by user id) is
   auto-promoted — same rule in both paths, per `BOARD-SHARING.md` §6. A *sole member* cannot
   "leave": deleting the board is the honest action (the UI says so). Leaving your **last** board is
   refused (`LastBoardException` pattern) to preserve the ≥1-board invariant.
7. **Assignee is open coordination, not permission.** Any member may set a task's assignee to any
   member or clear it. It's a double-scheduling guard among people who share a household, not an
   ACL; locking it down ("only self-claim") adds friction with no threat model behind it. The UI
   leads with claim/unclaim; assigning someone else is possible but secondary.
8. **Scheduling claims, nothing auto-unclaims.** When a member's planner schedules a shared task
   into their week, stamp `assignee_user_id = me` if currently null (alongside the existing
   `last_scheduled_in_session_id` stamp). Removing a task from a plan does **not** auto-clear the
   assignee — too many subtle paths (carry-over, reschedule, session abort); explicit unclaim is one
   click. Completion keeps the assignee as a "who did it" record.
9. **Planner fairness caps: deferred.** With boards annotated in the prompt and a human steering
   the conversation, starvation is hypothetical at the current user count. `PlannerTaskSelector` is
   the single place a per-board cap would go; we add it if a real transcript shows a busy board
   crowding out a quiet one. (Tracked in `BOARD-SHARING.md` open questions.)
10. **Auto-archive applies to boards you OWN.** Resolves the Phase-0 flag: the per-user
    `auto_archive_days` setting sweeping *member* boards would let the most aggressive member
    archive everyone's DONE tasks. Owner-scoped is unambiguous for single-owner boards (identical to
    today) and "owner policy wins" is the least surprising rule for shared ones. With co-owners,
    any owner's setting can archive — acceptable; owners are peers.
11. **Inviting requires a real, durable account.** The inviter must be an OWNER **and** have at
    least one auth identity (demo users deliberately have none — an ephemeral 24-h account must not
    pull strangers into a board that evaporates). Member cap of **10 per board** and invite rate
    limits (below) as abuse guards.
12. **Consent dialog on every invite send.** It doubles as the address confirmation, and the cost
    of re-reading two sentences is nil next to the cost of a quiet over-share. Copy per
    `BOARD-SHARING.md` §8: *everyone you add can see and edit every task on this board, including
    ones already there, until you remove them* — plus a line that members will see your name.
13. **Packaging: three sequential PRs**, risk-first like Phases 0–1: the invisible data-model
    correction lands first, then membership machinery, then the cross-member task UX.

## PR 1 — board-keyed change feed (ships looking identical)

The deferred Phase-0 deviation, corrected. No visible behavior change for single-member boards
(except `has-changes` becoming exact instead of over-triggering across a user's boards).

### Schema (changeset `002-board-change-feed.xml`)

The baseline was consolidated into `changesets/001-schema.xml` at the Phase-0 reset, so this is the
first post-reset changeset; it must run cleanly on live prod data:

- `DELETE FROM backlog_task_change_event` / `backlog_task_watermark` (the decided one-time drop).
- `backlog_task_change_event`: add `board_id UUID not null` FK → `board(id)` (safe after the
  delete), index it; rename `user_id` → `actor_user_id`, make it nullable (keep the FK → `users`).
- `backlog_task_watermark`: drop and recreate keyed by `board_id` (PK, FK → `board(id)`). The
  table is a one-row-per-key timestamp; recreating is simpler than altering the PK.

### Backend

- `BacklogTaskChangeService`: `record*` take `(boardId, actorUserId, …)`; snapshots encrypt/decrypt
  via `boardCrypto`; `bumpWatermark(boardId)` / `changedSince(boardId, since)`;
  `summarizeSince(boardId, since)` decrypts under the board DEK. Callers
  (`BacklogTaskService`, `TaskAutoArchiveService`) already know the board — thread it through.
  `TaskAutoArchiveService`'s "bump once per user after sweeping" becomes bump-per-swept-board.
- Planner: the "what changed since last session" diff aggregates `summarizeSince` over
  `listBoardIds(userId)`, labeled per board like the candidate list. On shared boards include the
  actor's display name (resolution from PR 2; until then "someone" / omit — PR ordering keeps this
  trivial since boards are still single-member when PR 1 ships).
- `/boards/{boardId}/tasks/has-changes` reads the **board** watermark — the documented Phase-1
  over-trigger ends here, and a member's edit now refreshes other members' open tabs.
- `StatsService`: change-event queries move to `actor_user_id = :userId` (personal stats
  semantics preserved exactly).
- `AccountService.deleteUserData`: stop deleting change events by user; instead null
  `actor_user_id` on the departing user's events for boards that survive, and delete events/
  watermarks of boards that get deleted. `BoardService.deleteBoard`'s raw-SQL block adds the two
  tables (it predates them being board-keyed).

### Tests

- `BacklogTaskChangeServiceTest` re-pointed to board keying + board-DEK round-trip (AAD: another
  board's id must fail to decrypt a snapshot).
- Slice/integration: `has-changes` flips on another member's… not possible yet — instead assert it
  does **not** flip for a different board of the same user (the Phase-1 spurious case, now exact).
- Liquibase: changeset applies on a Postgres container seeded with pre-002 rows (TestContainers),
  proving the live-prod path.

## PR 2 — invitations, members, ownership transfer

The membership machinery, backend + frontend. After this PR a board can actually have two members.

### Schema (changeset `003-board-invitation.xml`)

```
board_invitation
  id                  UUID        PK
  board_id            UUID        not null, FK -> board(id), indexed
  email_hash          VARCHAR(64) not null            -- SHA-256, normalized; no plaintext stored
  token               VARCHAR(64) not null, unique    -- generated like email_login_token's
  invited_by_user_id  UUID        null, FK -> users(id)   -- null if inviter's account is deleted
  created_at          TIMESTAMP   not null
  expires_at          TIMESTAMP   not null            -- created_at + 7 days
  consumed_at         TIMESTAMP   null                -- accepted (accepted_by set)
  revoked_at          TIMESTAMP   null                -- owner cancel / superseded by re-invite
  accepted_by_user_id UUID        null, FK -> users(id)
```

Status is derived (pending = not consumed, not revoked, not expired) — no enum column to keep in
sync. "One pending invite per (board, email)" is enforced in the service (re-invite revokes the
prior one), not by a partial unique index (H2/Postgres parity).

### Backend — `BoardInvitationService` + endpoints

Mirrors `EmailLoginService` (token shape, consume-before-resolve, rate limiting via
`countBy…CreatedAtAfter`, encrypted-at-rest posture — here nothing sensitive is stored at all,
only the hash).

| Endpoint | Auth | Behavior |
|---|---|---|
| `POST /api/v1/boards/{boardId}/invitations` `{email}` | OWNER | guards: inviter has an auth identity (Decision 11); member cap 10 (members + pending); invitee not already a member (409); re-invite revokes prior pending. Rate limits: per-board/day and per-inviter/day (reuse the count-rows pattern; ~10/day each). Sends localized invite email via the **auth** sender (inviter's locale — best guess for a household); template `emails/board-invitation.html`, new `email.boardInvite.*` message keys. Counted by the existing `tasker.email.sent` metric. |
| `GET /api/v1/boards/{boardId}/invitations` | OWNER | pending invitations (masked email reconstructed? **No** — we don't store plaintext; return `email_hash`-keyed entries with created/expires only, plus a label the *client* remembers? Simplest honest answer: show "invited &lt;date&gt;, expires &lt;date&gt;" without the address; the owner just typed it. If that proves confusing, store the address encrypted under the **board DEK** in a later changeset — owners may see it, it's board data. Start without it.) |
| `DELETE /api/v1/boards/{boardId}/invitations/{id}` | OWNER | revoke. |
| `GET /api/v1/invitations/{token}` | none | preview for the accept screen: board name, inviter display name (Decision 4 resolution), expiry validity. The token *is* the authorization (Decision 3). Invalid/expired → uniform 404. |
| `POST /api/v1/invitations/{token}/accept` | session + CSRF | consume token (single-use, mark consumed **before** side effects, like `completeLogin`), re-validate member cap, create `MEMBER` membership (no-op 200 if already a member), set `accepted_by_user_id`. |

Acceptance does **not** need a board-DEK hand-off — keys are server-side, wrapped under the app KEK;
membership *is* the access grant. (This is why per-board encryption was designed this way in
Phase 0.)

### Backend — member management

| Endpoint | Auth | Behavior |
|---|---|---|
| `GET /api/v1/boards/{boardId}/members` | member | `[{ userId, role, joinedAt, displayName }]`, display names per Decision 4. Also serves assignee chips (PR 3). |
| `PATCH /api/v1/boards/{boardId}/members/{userId}` `{role}` | OWNER | promote/demote; refuse demoting the last OWNER (409). |
| `DELETE /api/v1/boards/{boardId}/members/{userId}` | OWNER | remove member (not self — use leave); refuse removing the last OWNER. Side effects below. |
| `POST /api/v1/boards/{boardId}/members/leave` | member | refuse if sole member ("delete the board instead") or last board (`LastBoardException`); if last OWNER with others → auto-promote then leave (Decision 6). |

**Removal/leave side effects** (one shared code path): delete the membership row, **clear
`assignee_user_id` on the board's tasks where it points at the departed user** (no ghost claims),
null their `actor_user_id` on the board's change events… no — events keep the actor while the
*user* exists; only account deletion nulls it. The departed member's personal plans survive
untouched: `planned_task` carries its own user-DEK ciphertext title and `backlog_task_id` is a soft
reference (verified — no FK), so plan rendering doesn't depend on board access. Cross-board task
resolution (`findTask`, stamp/clear paths) already scopes to current memberships, so a removed
member simply stops seeing the tasks; verify the stamp-clearing paths fail soft when a task is no
longer resolvable.

**`AccountService.deleteUserData`** gets the real §6 logic (the Phase-0 forward-compat shape slots
in): for each membership — sole member → delete board + content (current behavior); last OWNER with
others → auto-promote, then remove membership + clear assignee stamps + null actor ids; MEMBER →
remove membership + clear stamps + null actor ids. Also delete the user's *pending sent*
invitations (`invited_by_user_id`)? No — null it; the invitation should survive its sender like the
board does. `DemoCleanupService` inherits all of this for free.

### Frontend

- **Members panel**: a `BoardMembersModal` (sibling of `SettingsModal`), opened from the
  `BoardSwitcher` menu ("Members…"). Lists members (name, role chip, joined date), owner-only:
  invite button, remove, role toggle; everyone: leave. Pending invitations section for owners.
- **Invite flow**: email input → **consent dialog** (Decision 12 copy) → send → pending list.
- **Accept screen**: new top-level route `/invite` (register in `SpaForwardController`). Reads
  `?token=…`, calls the preview endpoint, then:
  - **Authenticated** → "Join *Home* — invited by Dana. You're signed in as &lt;identity&gt;." +
    accept button (the consent counterpart: your name becomes visible to members) → POST accept →
    switch `activeBoardId` to the new board.
  - **Unauthenticated** → same preview + the login options (reuse `LoginPage` pieces). The
    magic-link leg needs a whitelisted-relative `next` parameter so the user lands back on
    `/invite?token=…` — provided by the email-link confirmation work
    (`docs/EMAIL-LINK-CONFIRMATION.md`), which should land **before** this PR; it also makes the
    login leg safe against email link-protection scanners. The Telegram widget flow handles `next`
    client-side (state/localStorage).
- `api.ts`: `fetchMembers`, `inviteToBoard`, `revokeInvitation`, `removeMember`, `leaveBoard`,
  `setMemberRole`, `fetchInvitationPreview`, `acceptInvitation`.
- `BoardSwitcher`: small member-count badge on shared boards (cheap "this one is shared" signal).

### Tests

- `BoardInvitationServiceTest` on the `EmailLoginServiceTest` model: token round-trip, replay,
  expiry, revoke-on-reinvite, rate limits, member cap, demo-user (no identity) refusal.
- Member-management slice tests: last-OWNER guards, leave guards, 403s for non-owners.
- Deletion: unit tests for the three `deleteUserData` branches incl. auto-promotion order;
  integration test for the full invite → register-via-link → accept → both members see the task
  round-trip (Postgres).
- Display-name resolution fallbacks incl. email masking.

## PR 3 — claim/assignee + shared-board planner behavior

The cross-member task UX. `assignee_user_id` has existed (null) since Phase 0 — no schema change.

### Backend

- `PUT /api/v1/boards/{boardId}/tasks/{id}/assignee` `{userId | null}` (member; target must be a
  member or null — Decision 7). Bumps the board watermark (claims must propagate to other members'
  open tabs) but records **no change event** — claim churn would drown the planner's weekly diff.
- Task DTOs already carry `assigneeUserId`; ensure the web list/response includes it (frontend
  resolves names via the members endpoint, no joins server-side).
- **Planner selection**: `PlannerTaskSelector` filters the candidate pool to
  `assigneeUserId == null || assigneeUserId == userId` (the §7 rule: never offer a task someone
  else claimed). Prompt annotation for shared boards may mention "claimed by you" where relevant.
- **Scheduling stamps the claim**: wherever `last_scheduled_in_session_id` is stamped
  (`stampPlanningSession` + the web add-to-plan path), also set `assignee = userId` if null
  (Decision 8). Carry-over re-bumps don't change an existing assignee.
- **Auto-archive** switches to owned boards only (Decision 10) — `TaskAutoArchiveService` iterates
  memberships with `role == OWNER`.
- Export v2: tasks now emit real `assignee` values; import keeps mapping them to `null` (the format
  reserved the field; cross-account user ids are meaningless on import — documented in
  `export-format-v2.md`).

### Frontend

- **Assignee chip** (initials/avatar from the member display name) on task cards and the task edit
  modal — rendered only when the board has >1 member, so single-member boards look exactly like
  today.
- **Claim / unclaim** action on the card menu; "assign to…" picker (members list) in the edit
  modal.
- Poll-refresh already exists via `has-changes`; the watermark bump on assignee writes makes claims
  show up across members without new plumbing.

### Tests

- Selector exclusion matrix (null / mine / other's), stamp-sets-claim, stamp-preserves-claim.
- Assignee endpoint: non-member target 400/409, non-member caller 403, watermark bumped.
- Auto-archive: member (non-owner) setting no longer archives the shared board.

## Considerations & pitfalls (cross-PR)

- **Additive-only is back in force.** No reset this phase; every changeset must be rehearsed
  against a database containing real pre-Phase-2 rows (the PR 1 TestContainers migration test is
  the guard). The one data drop (change feed) is deliberate and bounded.
- **The capability-token trade-off** (Decision 3) is the security-sensitive call of this phase.
  Mitigations that must actually ship together: single-use consume-before-side-effects, 7-day TTL,
  uniform 404 for invalid tokens, rate-limited send, owner-visible member list, easy removal.
- **Cross-user decryption** (Decision 4) widens the de-facto privacy boundary from "your data, your
  request" to "board-visible profile basics, any member's request". Keep the surface minimal: name
  resolution only, full email never, nothing from `user_settings` beyond `display_name`.
- **Locale of the invite email** is a guess (inviter's). Wrong-language invites are possible;
  acceptable for v1, the email is two sentences.
- **Concurrent edits** stay last-write-wins (`updated_at`); claims reduce the practical collision
  surface. Revisit only on real complaints.
- **Watermark polling frequency** is unchanged, but shared boards now genuinely cross-trigger
  refreshes — that's the feature, not a bug; volume is household-scale.
- **Copy duties**: the consent dialog (invite), the accept screen (name visibility), board deletion
  by an owner of a *shared* board ("N other members will lose access"), leave ("your tasks stay"),
  and account deletion ("tasks you added to shared boards remain") all carry the §6/§8 privacy
  promises — they're part of the work, not polish.

## Out of scope (deferred beyond Phase 2)

- Planner fairness caps (Decision 9 — add on evidence).
- Shared-board export/import round-trip semantics (who re-creates a shared board; `BOARD-SHARING.md`
  open question — export keeps emitting every board with your role, import keeps creating you as
  sole OWNER).
- Storing the invited address (encrypted under the board DEK) for the pending-invitations list —
  only if the date-only listing proves confusing.
- Viewer/commenter role tiers, per-task permissions — only on demonstrated need.
- Surfacing other members' claimed tasks to the planner as context (default remains: excluded).
