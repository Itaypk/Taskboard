# Board sharing — Phase 1 (multi-board, single user)

Status: **Implementation plan — in progress.** Phase 1 of `docs/BOARD-SHARING.md`. Phase 0 (the
invisible board ownership model, `docs/BOARD-SHARING-PHASE0.md`) is merged and live in production.
Phase 1 makes boards *visible*: a user can have several, switch between them, and the planner spans
all of them. No sharing yet — every board still has exactly one member.

## Decisions settled at Phase 1 start

These resolve the open questions `BOARD-SHARING.md` deferred to "Phase 1 start":

1. **Board-in-request convention: path-scoped.** Board-owned content moves under
   `/api/v1/boards/{boardId}/…`. Explicit, stateless, and centrally guardable with
   `requireMember`. The old unscoped paths (`/api/v1/tasks`, `/categories`, `/tags`) are **deleted
   in the same PR** — the frontend is bundled into the backend, so the switch is atomic; there are
   no third-party API consumers.
2. **Non-member access returns 403** via `BoardAccessDeniedException`. `requireMember` throws the
   same exception whether the board doesn't exist or simply isn't yours, so the response doesn't
   leak board existence. (The exception is currently unmapped — latent 500 — and gets an exception
   mapping in PR 1.)
3. **Default board rule.** Task writes that arrive without a board context (the planning
   conversation's `CreateTaskTool`, any future quick-capture channel) land on the user's **default
   board = the oldest membership** (`min(joined_at)`, tie-broken by board id). Because planner
   candidates are annotated with board names, the create-task tool also accepts an **optional board**
   so the model can honor "add it to my Home list". No persisted `default_board_id` setting unless
   real usage demands one.
4. **Packaging: three sequential PRs** (below), each shippable, mirroring the Phase-0 strategy of
   landing risk early and invisibly.

## PR 1 — board-scoped API (ships looking identical)

The mechanical request-plumbing change, isolated from any behavior change.

### Backend

- **`BoardController`** (new): `GET /api/v1/boards` → `[{ id, name, role, createdAt }]`, name
  decrypted via `BoardCryptoService`. Read-only in this PR (create/rename/delete come in PR 2 with
  the UI that needs them).
- **Path-scoping** (old path → new path, handler unchanged):

  | Today | PR 1 |
  |---|---|
  | `GET/POST /api/v1/tasks` | `GET/POST /api/v1/boards/{boardId}/tasks` |
  | `PUT/DELETE /api/v1/tasks/{id}` | `…/boards/{boardId}/tasks/{id}` |
  | `PATCH /api/v1/tasks/{id}/reorder` | `…/boards/{boardId}/tasks/{id}/reorder` |
  | `DELETE /api/v1/tasks/{id}/plan-schedule` | `…/boards/{boardId}/tasks/{id}/plan-schedule` |
  | `GET /api/v1/tasks/has-changes` | `…/boards/{boardId}/tasks/has-changes` (see note) |
  | `GET/POST /api/v1/categories`, `PUT/DELETE /…/{id}` | `…/boards/{boardId}/categories…` |
  | `GET /api/v1/tags` | `…/boards/{boardId}/tags` |

  Stays user-scoped (not board content): `/api/v1/stats` (aggregates the user's boards from PR 2 on),
  `/api/v1/plans…`, `/api/v1/planning…`, `/api/v1/settings…`, `/api/v1/account…`, `/api/auth/…`.

  > **`has-changes` note:** the path is board-scoped now (so the API doesn't move twice), but the
  > backing watermark stays per-user (the Phase-0 deviation). A change on board A may spuriously
  > refresh a tab showing board B — harmless over-trigger, becomes exact when Phase 2 re-keys the
  > watermark to the board.

- **Authorization in the service layer** (single choke point): the web-facing methods of
  `BacklogTaskService` / `BacklogTaskCategoryService` / `BacklogTaskTagService` change signature
  from `(userId, …)` to `(userId, boardId, …)` and open with
  `boardMembershipService.requireMember(userId, boardId)`. The `userId` parameter remains for the
  watermark/change-event actor and (later) assignee stamping — exactly the §2 split in
  `BOARD-SHARING.md`. Planner-facing methods (`stampPlanningSession`,
  `clearPlanningSessionStamp`, `getTasksScheduledInSession`, `getTaskById` as used by planning
  tools) keep their `userId`-only signatures with `resolveSoleBoard` inside until PR 3 moves them.
- **`BoardAccessDeniedException` → 403** mapping (currently unmapped).
- Drive-by privacy fix: `BacklogTaskController` logs the task title on create
  (`logger.info("Creating backlog task: ${request.title}")`) — drop the title per the no-sensitive-
  data-in-logs rule.

### Frontend (mechanical, no visible change)

- `api.ts`: add `fetchBoards()`; task/category/tag functions take a `boardId` first argument.
- `App.tsx` / auth bootstrap: after auth, fetch boards, hold `activeBoard` in state (the sole
  board), gate the initial task/category load on it, thread it into every call. No switcher UI.

### Tests

- Slice tests move to the new paths and mock `requireMember`; add the 403 non-member case.
- New `BoardControllerTest` (list returns the user's boards with decrypted names).
- `SecurityIntegrationTest` / `PostgresIntegrationTest` path updates; full-round-trip
  register → board listed → CRUD through the scoped paths.

## PR 2 — multi-board for real

- **Board CRUD**: `POST /api/v1/boards` (create + DEK + OWNER membership + default categories —
  `BoardService.createBoardForOwner` already does all of it), `PATCH /api/v1/boards/{id}` (rename,
  re-encrypts `board.name`), `DELETE /api/v1/boards/{id}` (owner-only; refuse deleting the **last**
  board to preserve the ≥1-board invariant; deletes content + `board_data_key`, reusing the
  deletion logic shape in `AccountService.deleteUserData`).
- **Retire `resolveSoleBoard`**: becomes `resolveDefaultBoard` (oldest membership) and each
  remaining caller is dispositioned:

  | Caller | Disposition |
  |---|---|
  | `TaskAutoArchiveService` | iterate **all** the user's boards (per-user setting applied to each owned board; revisit at Phase 2 for shared boards) |
  | `StatsService` | sum task counts/events across **all** memberships; planning counts stay per-user |
  | `AccountService.exportAccount` | emit **every** board into `boards[]` (format already allows it) |
  | `AccountService.isEmptyForImport` | "empty" = exactly one board with only the seeded defaults |
  | `AccountImportService` | first exported board → the existing default board; additional boards → created (forward-compat; exports made before PR 2 only have one) |
  | `DemoDataSeeder`, `DevPlanningController`, `PlanningSessionService`, `PlannerTaskSelector` | `resolveDefaultBoard` until PR 3 (planner) / stay on default board (demo) |

- **Frontend**: board switcher in the shell (one active board at a time, per the design), create /
  rename / delete UI, persist last active board in `localStorage` (per-device is fine; server-side
  persistence only if it proves annoying).

## PR 3 — the planner spans boards

- `PlannerTaskSelector.select` unions TODO tasks across all memberships; each candidate carries its
  board id + name. Selection slots stay global (urgency competes across boards); per-board caps are
  a Phase-2 tuning item per `BOARD-SHARING.md`.
- `WeeklyPlanningPromptAssembler` annotates candidates with the board name when the user has >1
  board (single-board users see no change in their prompt).
- `CreateTaskTool` / `UpdateTaskTool` / `BacklogTaskSearchAgent`: operate across the user's boards;
  creation defaults to the default board with an optional model-provided board (decision 3 above).
- `BacklogTaskService` planner-facing methods take explicit board ids; `resolveSoleBoard` is gone.
- Change summaries (`summarizeSince`) stay per-user — correct while boards are single-member;
  Phase 2 moves them to the board.

## Out of scope (Phase 2 — sharing)

Invitations + consent dialog, `MEMBER` role enforcement beyond owner-only board ops, Members panel,
claim/assignee writes + UI, watermark/change-event re-key to the board, ownership transfer on
account deletion, member display-name endpoint, planner fairness caps.
