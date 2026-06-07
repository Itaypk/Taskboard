# Board sharing — Phase 0 (invisible foundation)

Status: **Implementation plan for the risky refactor branch.** Phase 0 of `docs/BOARD-SHARING.md`.
This branch is **not** prod-safe on its own — it changes the ownership model and re-keys task
encryption, and must go through extensive local testing. Production is migrated separately via a
**reset + reseed**, bridged by the export/import flow specified in `docs/export-format-v2.md`.

## Goal & guiding invariant

Introduce the board ownership model **without any user-visible change**. After Phase 0:

> **Every user belongs to exactly one board, as its `OWNER`, and that board holds all of their
> tasks/categories/tags.** The app looks and behaves identically to today.

This lands the pervasive, scary part (new ownership FK + per-board encryption) behind a no-op, so
Phases 1–2 (multi-board, sharing) are incremental on a proven base.

## Strategy: confine the diff to persistence + service + crypto

**The controller/API surface and the frontend do not change in Phase 0.** Controllers keep
injecting `@AuthenticationPrincipal principal: TaskerPrincipal` and calling services with
`principal.userId`. The board is resolved **inside the service layer**:

```kotlin
// BoardMembershipService
fun resolveSoleBoard(userId: UUID): UUID            // Phase-0 invariant: exactly one board
fun requireMember(userId: UUID, boardId: UUID): BoardRole   // throws 403/404 otherwise
```

In Phase 0 `requireMember` is trivially satisfied (one board, you're its owner). The
board-in-request API decision (path-scoped vs active-board-in-session — see `BOARD-SHARING.md` §4)
is **deferred to Phase 1**, when multiple boards actually exist. This keeps Phase 0's blast radius
in the data/service/crypto layers, where the real work is.

## 1. New tables (changesets `007` + `008`)

Add **new** changesets (never edit `001`–`006`; Liquibase checksums + dev/test replay depend on
it). Because prod is **reset + reseeded**, these run against an empty DB — so they need **no data
backfill**, and dropping the now-unused `user_id` columns is safe (empty tables).

`007-boards.xml`:

```
board
  id          UUID       PK
  name        ${blob}    -- nullable; encrypted under the board DEK (members decrypt). See §3.
  created_at  TIMESTAMP  not null

board_membership
  id         UUID       PK
  board_id   UUID       not null, FK -> board(id)
  user_id    UUID       not null, FK -> users(id)
  role       VARCHAR    not null   -- 'OWNER' | 'MEMBER'
  joined_at  TIMESTAMP  not null
  -- unique (board_id, user_id); index (user_id)

board_data_key                     -- mirrors user_data_key exactly, keyed by board
  board_id      UUID       PK, FK -> board(id)
  wrapped_dek   ${blob}    not null
  kek_version   INT        not null
  created_at    TIMESTAMP  not null
```

`008-board-owned-content.xml` — repoint ownership on content tables:

| Table | Add | Drop |
|---|---|---|
| `backlog_task` | `board_id UUID not null` (FK → board), `assignee_user_id UUID null` (FK → users) | `user_id` column + its FK |
| `backlog_task_category` | `board_id UUID not null` (FK → board) | `user_id` column + its FK |
| `backlog_task_tag` | `board_id UUID not null` (FK → board) | `user_id` column + its FK |
| `backlog_task_change_event` | `board_id UUID not null` (FK → board) | `user_id` column + its FK |
| `backlog_task_watermark` | re-key to `board_id` (PK/owning column) | `user_id` |

> Use the project's `LONGVARCHAR`/blob conventions for the wrapped key and `board.name` (match
> `user_data_key` and the existing `BYTEA` task columns). `assignee_user_id` is added now (cheap)
> but is only *written/used* from Phase 2; in Phase 0 it stays null.

Stays **user-owned** (personal plan/calendar — unchanged): `planning_session`, `planned_task`,
`planned_task_slot`, `ai_conversation`, `ai_message`, `user_settings`, `auth_identities`,
`user_data_key`.

## 2. Per-board encryption (`BoardCryptoService`)

Add a `BoardCryptoService` that is a near-copy of `UserCryptoService`, with **`board_id` bound as
AAD** instead of the user id, backed by `board_data_key`:

- `ensureBoardKey(boardId)` — called on board creation (mirrors `ensureUserKey`).
- `encrypt(boardId, plaintext)` / `decrypt(boardId, ciphertext)`.

**Re-keys to the board DEK** (board-owned ciphertext): `backlog_task.title`, `backlog_task.description`,
`backlog_task_change_event.task_title_snapshot`, and `board.name`.

**Stays under the user DEK** (`UserCryptoService`, unchanged): `users.email`,
`users.telegram_first_name`, all of `user_settings` (`display_name`, `context_block`,
`agent_description`), and **`planned_task.title`/`notes`** (see the gotcha in §7).

> There is no in-place re-encryption of existing ciphertext. On the prod reset, content is wiped
> and re-imported; import re-encrypts task content under the new board DEK from plaintext
> (`docs/export-format-v2.md`). Dev/test start empty.

## 3. Entity & domain-model changes

Entities (`jpa/`):
- `BacklogTaskEntity`: `userId` → `boardId`; add `assigneeUserId: UUID?`.
- `BacklogTaskCategoryEntity`, `BacklogTaskTagEntity`: `userId` → `boardId`.
- `BacklogTaskChangeEventEntity`: `userId` → `boardId`.
- `BacklogTaskWatermarkEntity`: key on `boardId`.
- New: `BoardEntity`, `BoardMembershipEntity`, `BoardDataKeyEntity`, `BoardRole` enum.

Domain models (`model/BacklogTask.kt`):
- `BacklogTask`: `userId` → `boardId`; add `assigneeUserId: UUID?`.
- `BacklogTaskCategory`, `BacklogTaskTag`: `userId` → `boardId`.

Mapper (`jpa/BacklogTaskMapper.kt`): `toDomain` switches from `crypto.decrypt(userId, …)` to
`boardCrypto.decrypt(boardId, …)`; the `ownerId` guard becomes a `boardId` guard.

## 4. Repository changes

`BacklogTaskRepository` — rename the `UserId` scoping to `BoardId` (Spring Data derives the new
queries automatically):

| Today | Phase 0 |
|---|---|
| `findAllByUserIdOrderBySortKeyAsc` | `findAllByBoardIdOrderBySortKeyAsc` |
| `findAllByUserIdAndStatus` | `findAllByBoardIdAndStatus` |
| `findAllByUserIdAndStatusOrderBySortKeyAsc` | `findAllByBoardIdAndStatusOrderBySortKeyAsc` |
| `findAllByUserIdAndStatusNotOrderBySortKeyAsc` | `findAllByBoardIdAndStatusNotOrderBySortKeyAsc` |
| `findAllByUserIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc` | `…ByBoardIdAndStatusAndUpdatedAtBefore…` |
| `findAllByUserIdAndLastScheduledInSessionIdOrderBySortKeyAsc` | `…ByBoardIdAndLastScheduledInSessionId…` |
| `findAllByUserIdAndIdIn` | `findAllByBoardIdAndIdIn` |
| `findByIdAndUserId` | `findByIdAndBoardId` |
| `countByUserIdAndStatus` | `countByBoardIdAndStatus` |
| `existsByCategoryIdAndUserId` | `existsByCategoryIdAndBoardId` |
| `deleteAllByUserId` | `deleteAllByBoardId` |
| `findMaxSortKeyByUserId` | `findMaxSortKeyByBoardId` |

Same `UserId` → `BoardId` rename for `BacklogTaskCategoryRepository`,
`BacklogTaskTagRepository`, and the change-event/watermark repositories. Add
`BoardRepository`, `BoardMembershipRepository`, `BoardDataKeyRepository`.

## 5. Service changes

- **New `BoardService`** — `createBoardForOwner(userId, name): UUID`:
  1. insert `board` (+ encrypted `name`), 2. `boardCrypto.ensureBoardKey(boardId)`,
  3. insert `OWNER` `board_membership`, 4. seed `UserService.DEFAULT_CATEGORIES` **into the board**.
- **New `BoardMembershipService`** — `resolveSoleBoard(userId)` / `requireMember(userId, boardId)`.
- `BacklogTaskService`, `BacklogTaskCategoryService`, `BacklogTaskTagService` — public methods keep
  taking `userId` at the boundary, internally `resolveSoleBoard(userId)` then operate by `boardId`.
  Task content encrypt/decrypt switches to `boardCrypto`. (`createTask`/`updateTask` resolve the
  category by `findByIdAndBoardId`; tag resolve/create by `boardId`.)

## 6. Registration & seed hooks

- **`UserService.initializeNewUser(userId)`** — today seeds default categories for the user. Change
  to: `boardService.createBoardForOwner(userId, DEFAULT_BOARD_NAME)` (which now owns the default
  categories) + `userSettingsService.initializeForNewUser(userId)`. This single change covers the
  real registration path, since `UserAuthService.loginOrRegister`, `ensureDevUser`, and
  `createDemoUser` all funnel through `initializeNewUser`.
- **`DemoDataSeeder.seed(userId)`** — resolve the demo user's board, encrypt seed task content under
  the **board** DEK, set `boardId` on seeded tasks/categories lookups. **Fix the ciphertext-copy
  shortcut** (lines ~128–130): it currently copies the task's ciphertext title verbatim into
  `planned_task.title` ("same user → same key"). With a board DEK that's now a *different* key from
  the user-DEK that `PlannedTaskService` uses for `planned_task.title`. Change it to
  `userCrypto.encrypt(userId, plaintextTitle)` so demo `planned_task` rows match the real flow.
- `DevDataInitializer` / `ensureDevUser`, `createDemoUser` — no direct change beyond the
  `initializeNewUser` update; verify the dev/demo user ends up with exactly one board.

## 7. The `planned_task` re-keying gotcha (verified)

`PlannedTaskService` encrypts/decrypts `planned_task.title` and `planned_task.notes` with
`userCrypto.decrypt(userId, …)` / `encrypt(userId, …)` from the **plaintext** agreed-plan title —
so in the real flow `planned_task` content is genuinely **user-DEK** encrypted and stays that way.
**No `board_id` is needed on `planned_task`.** The *only* place that violates this is
`DemoDataSeeder`'s verbatim ciphertext copy (fixed in §6). No other planning code copies task
ciphertext across rows — confirm with a grep for `\.title` assignments during review.

## 8. Read-path services

- **`StatsService`** — counts/events are per `user_id` today. Repoint to the user's board(s):
  Phase 0 has exactly one, so sum `countByBoardIdAndStatus` and read change events by `board_id`
  over `resolveSoleBoard(userId)` (write it to iterate the user's memberships so it already works
  in Phase 1). `planning_session` counts stay per-user.
- **`TaskAutoArchiveService`** — driven by the per-user `auto_archive_days` setting, operating on
  tasks now owned by a board. Phase 0 (1 board per owner) is unambiguous: archive DONE tasks in the
  user's board past the cutoff. **Flag for Phase 2:** auto-archive is a per-user setting but a
  shared board's tasks are common — decide then whether archiving is board-level or per-member.
- **`BacklogTaskChangeService`** — record events + bump the watermark by `board_id`; title
  snapshots encrypt under the board DEK. `summarizeSince`/`changedSince` take `boardId`. This is
  what lets a member's edit refresh another member's tab later (Phase 2), and is correct now.

## 9. Account deletion (`AccountService.deleteUserData`)

Phase 0 logic (one board per user): delete the user's board content **and** the board itself —
tasks, `backlog_task_tags` join rows, categories, tags, change events, watermark, `board_data_key`,
`board_membership`, `board`. Then the existing user-scoped deletes (sessions, planning_session,
planned_task(+slot), ai_conversation, auth_identities, user_data_key, user_settings).

> **Forward-compatibility note:** write the board cleanup as "for each board the user owns/belongs
> to, delete iff sole member; else remove membership" even in Phase 0. With one sole-owned board it
> reduces to "delete the board", but it's the exact shape Phase 2's ownership-transfer logic
> (`BOARD-SHARING.md` §6) slots into — avoids a rewrite.

`DemoCleanupService` calls `deleteUserData` per expired user, so it inherits the board cleanup for
free.

## 10. Out of scope for Phase 0 (deferred)

- Board-in-request API change / path scoping (`/boards/{id}/...`) → **Phase 1**.
- Multi-board UI, board switcher, board CRUD → **Phase 1**.
- Invitations, consent dialog, `MEMBER` role, ownership transfer, assignee **UI/writes** → **Phase 2**
  (the `assignee_user_id` column is added now but stays null).
- Cross-board planner candidate selection → **Phase 1** (Phase 0 still selects from the one board).

## 11. Testing (this branch's whole point)

- **Unit:** update `BacklogTaskServiceTest`, `UserAuthServiceTest` for board resolution; new
  `BoardServiceTest`, `BoardCryptoServiceTest` (AAD round-trip: a board's ciphertext can't be
  decrypted under another board's id, mirroring the existing user-AAD test).
- **Slice:** existing controller tests should pass **unchanged** (the API didn't move) — that's the
  proof Phase 0 is invisible. Adjust only fixtures that seed tasks directly.
- **Integration:** `PostgresIntegrationTest` CRUD against real Postgres with the new schema;
  re-run `SecurityIntegrationTest`. Add a round-trip: register → default board exists with default
  categories → create/list/update/delete a task → stats/auto-archive behave as before.
- **Migration rehearsal:** stand up the current (pre-board) build, create data, `GET /export`
  (v2 — see `docs/export-format-v2.md`), wipe, bring up this branch, register, `POST /import`,
  assert the board + content reconstructed and task content decrypts under the board DEK.

## 12. Suggested PR/commit breakdown

1. Schema changesets `007`/`008` + `Board*`/`BoardDataKey` entities + repositories.
2. `BoardCryptoService` (+ tests).
3. `BoardService` + `BoardMembershipService`; wire `initializeNewUser`.
4. Repoint `BacklogTask*` entities/repos/services + mapper to `board_id`; `BoardCrypto` for content.
5. Repoint change-event/watermark + `StatsService` + `TaskAutoArchiveService`.
6. `DemoDataSeeder` board-awareness + ciphertext-copy fix; `AccountService.deleteUserData`.
7. Test sweep + migration rehearsal.

Each step compiles; the app is fully working (single implicit board) by the end of step 6.
