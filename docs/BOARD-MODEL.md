# Board model

How task ownership, multi-board, and sharing work today. This is current-state documentation, not
a plan — for the design rationale and phased rollout that produced this model, see
`docs/archive/BOARD-SHARING.md` and its Phase 0/1/2 companions. Open follow-ups are tracked as GitHub
issues.

## The core idea

A **board**, not a user, owns tasks/categories/tags. A user belongs to one or more boards via
`board_membership`. Privacy is emergent rather than a flag: **a board with exactly one member is
private; a board with more than one member is shared.** There is no separate "personal" board
type — the board created at registration is an ordinary board like any other.

## Schema

```
board
  id          UUID  PK
  name        BYTEA      -- encrypted under the board DEK
  created_at  TIMESTAMP

board_membership
  id         UUID  PK
  board_id   UUID  FK -> board(id)
  user_id    UUID  FK -> users(id)
  role       VARCHAR   -- OWNER | MEMBER, unique (board_id, user_id)
  joined_at  TIMESTAMP

board_invitation
  id                  UUID  PK
  board_id            UUID  FK -> board(id)
  email_hash          VARCHAR(64)   -- SHA-256, normalized; no plaintext stored
  token               VARCHAR(64) unique
  invited_by_user_id  UUID null FK -> users(id)
  created_at / expires_at (created_at + 7 days) / consumed_at / revoked_at / accepted_by_user_id
```

`backlog_task`, `backlog_task_category`, `backlog_task_tag`, `backlog_task_change_event`, and
`backlog_task_watermark` are all keyed by `board_id` (not `user_id`). `backlog_task` additionally
carries `assignee_user_id` (nullable — see [Claim/assignee](#claimassignee)).
`backlog_task_change_event.actor_user_id` (nullable) records who made a change, independent of
board membership, so it survives the actor leaving.

Stays **user**-scoped, never board-scoped: `planning_session`, `planned_task`,
`planned_task_slot` (your personal plan/calendar), and `user_settings`. The planner reads from a
possibly-shared backlog but plans your own week.

## Encryption

Two independent envelope scopes:

- **Per-user DEK** (`user_data_key`, `UserCryptoService`) — personal data: `users.email`,
  `users.telegram_first_name`, all of `user_settings` (`display_name`, `context_block`,
  `agent_description`).
- **Per-board DEK** (`board_data_key`, `BoardCryptoService`, AAD = board id) — board-owned
  content: `backlog_task.title`/`description`, `backlog_task_change_event.task_title_snapshot`,
  `board.name`. Created alongside the board, so board content survives any individual member
  leaving — the key belongs to the board, not a person.

## Authorization

Board-owned content lives under `/api/v1/boards/{boardId}/…`. Every board-scoped service method
opens with `BoardMembershipService.requireMember(userId, boardId)`, which throws the same
`BoardAccessDeniedException` (mapped to 403) whether the board doesn't exist or simply isn't
yours — the response never reveals board existence to a non-member. Owner-only actions (rename,
delete, invite, manage members) additionally assert `role == OWNER`; `BoardOwnerRequiredException`
→ 403.

## Roles

Deliberately minimal — `OWNER` and `MEMBER`, no viewer/commenter tier:

- **MEMBER**: full CRUD on the board's tasks/categories/tags.
- **OWNER**: everything a member can do, plus rename/delete the board and manage members
  (invite, remove, change role). Multi-owner is allowed — the invariant is "≥ 1 OWNER", not
  "exactly 1".
- A user must always belong to at least one board: deleting your last board, or leaving your last
  board, is refused (`LastBoardException`).
  Leaving as the last OWNER while other members remain auto-promotes the longest-tenured member
  (`min(joined_at)`, tie-break by user id) first — the same rule applies when that owner's account
  is deleted instead of leaving explicitly.

## Invitations

`BoardInvitationService` (mirrors the email magic-link design): an OWNER invites by email
(`POST /api/v1/boards/{boardId}/invitations`), which requires the inviter to have a real auth
identity (demo users cannot invite) and enforces a member cap of **10** (members + pending
invites) per board. The invite is a **capability token** — single-use, 7-day TTL, `email_hash`
only (no plaintext stored) — sent via the auth email sender. Deliberately, the *acceptor* doesn't
have to be signed in with the invited address: whoever presents the token may accept with
whichever account they're currently signed into (the accept screen shows which account that is).
A consent dialog is shown on every invite send, spelling out that new members can see and edit
every existing task on the board.

Endpoints: `POST/GET /api/v1/boards/{boardId}/invitations`, `DELETE …/invitations/{id}` (owner,
revoke), `GET /api/v1/invitations/{token}` (public preview), `POST /api/v1/invitations/{token}/accept`
(authenticated).

Member display names shown to co-members resolve server-side, in order:
`display_name` → `telegram_first_name` → `telegram_username` → masked email (`it***@gmail.com`) →
`"Member"`. Full email is never exposed to a co-member.

## Claim/assignee

`backlog_task.assignee_user_id` is the one cross-member coordination primitive — not a permission,
just a double-scheduling guard:

- `null` = unclaimed/up for grabs; any member may set it to any current member or clear it
  (`PUT /api/v1/boards/{boardId}/tasks/{id}/assignee`).
- Automatically stamped to the acting user when their planner schedules the task into their week
  (alongside `last_scheduled_in_session_id`), if not already set. Removing a task from a plan does
  **not** auto-clear the assignee — completion keeps it as a "who did it" record.
- The planner's candidate pool excludes tasks assigned to *other* members (unassigned + assigned
  to you are both eligible).

## The planner across boards

`PlannerTaskSelector` unions TODO tasks across every board the user is a member of, annotates each
candidate with its board name (only shown when the user has >1 board), and excludes tasks another
member has claimed. Cross-board scheduling preferences ("home chores only on weekends") are
expressed in the user's own words via the personal context block — there is no per-board planning
configuration or per-board agent persona.

## Frontend

`BoardSwitcher` in the app shell holds one active board at a time (persisted in
`localStorage.backlog.activeBoardId`); a unified "all boards" view is not built. `BoardMembersModal`
lists members and, for owners, exposes invite/remove/role-change; an `/invite?token=…` route
renders the accept screen for both signed-in and signed-out visitors. An assignee chip/claim
action appears on task cards only when a board has more than one member.

## Known gaps (tracked as GitHub issues)

- No per-board planner fairness caps — a busy board could in principle crowd out a quiet one in
  the candidate pool (#234).
- Shared-board export/import doesn't reconstruct membership: export emits every board you belong
  to with your role, import always recreates you as sole `OWNER` of each.
- No viewer/commenter role tier, no per-task permissions beyond the assignee primitive (#266).
