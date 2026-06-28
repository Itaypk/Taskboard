# Account Export Format v2

> **Superseded by `docs/export-format-v3.md`.** v3 drops internal database ids from the wire
> format (tasks reference category/tags by position) and adds a stricter email-import rule. The
> live code emits and accepts **v3 only**. This document is kept for historical context.

This specifies the JSON produced by `GET /api/v1/account/export` and consumed by
`POST /api/v1/account/import` for the **board-sharing migration**. It supersedes
`docs/export-format-v1.md` (which bridged the encryption migration). Read v1 first — **v2 is v1
reshaped to nest content under boards**, and the per-field semantics below defer to v1 wherever
they are unchanged.

## Why this exists & how it splits across branches

The board-sharing change (`docs/BOARD-SHARING.md`, Phase 0) re-keys task content from a per-user
DEK to a per-board DEK and repoints ownership from `user_id` to `board_id`. Per `CLAUDE.md`, prod
is migrated by **reset + reseed**, not an in-place migration. The export/import flow preserves beta
users' data across the wipe:

1. **Export side — ships on a prod-safe branch first.** It only *reads* existing per-user tables
   and reshapes the JSON; it makes **no** schema change and is safe to deploy to current production
   ahead of the board work. Users export their data while still on the pre-board build.
2. Deploy the board-aware build (the heavily-tested refactor branch) onto the wiped DB.
3. **Import side — lives on the board-aware build.** Users re-authenticate and `POST /import`;
   import recreates their board and re-encrypts content under the new board DEK.

> **Key simplification:** the export runs on the **pre-board** build, where there is no board
> concept and **every user owns exactly one logical board's worth of content**. So v2 export always
> emits **exactly one board** per account, synthesized by wrapping today's
> categories/tags/tasks. v2 does **not** need to represent multiple boards, shared boards, or
> multi-user round-trips — those don't exist at migration time. (The format *shape* allows more
> than one board for forward-compatibility, but the migration never produces it.)

There is no backward compatibility with v1. `formatVersion` is the version tag; import refuses
anything that isn't `2`.

## Version contract

- Top-level MUST include `"formatVersion": 2`.
- Import rejects missing/non-`2` `formatVersion` (HTTP 400).
- Treat the schema below as frozen for v2.

## Top-level shape

```jsonc
{
  "formatVersion": 2,
  "exportedAt": "2026-06-07T12:34:56.789Z",
  "user": { ... },          // identical to v1
  "settings": { ... } | null, // identical to v1
  "boards": [               // NEW: replaces top-level categories/tags/tasks
    {
      "name": "My tasks",
      "role": "OWNER",
      "categories": [ ... ], // identical to v1's categories[]
      "tags":       [ ... ], // identical to v1's tags[]
      "tasks":      [ ... ]  // v1's tasks[] + optional "assignee" (see below)
    }
  ]
}
```

- `user` and `settings` blocks are **byte-for-byte the v1 blocks** — they describe personal
  profile/settings, which stay user-owned and user-DEK-encrypted. See `export-format-v1.md`.
- `boards` **replaces** v1's top-level `categories` / `tags` / `tasks` arrays.

## Boards array

```jsonc
"boards": [
  {
    "name": "My tasks",
    "role": "OWNER",
    "categories": [ /* v1 category objects */ ],
    "tags":       [ /* v1 tag objects */ ],
    "tasks":      [ /* v1 task objects, plus optional assignee */ ]
  }
]
```

| Field | Type | Source on the export (pre-board) branch | Notes |
|---|---|---|---|
| `name` | String | constant default (e.g. `"My tasks"`) | pre-board build has no board name; import may keep it or the user renames later |
| `role` | String | always `"OWNER"` on export | `OWNER` \| `MEMBER`; only `OWNER` occurs at migration |
| `categories` | Array | `BacklogTaskCategoryRepository.findAllByUserId` | objects exactly as v1 |
| `tags` | Array | `BacklogTaskTagRepository.findAllByUserId` | objects exactly as v1 |
| `tasks` | Array | `BacklogTaskRepository.findAllByUserIdOrderBySortKeyAsc` | v1 task objects + `assignee` |

`categories[*]`, `tags[*]`, and `tasks[*]` field semantics, ID-map behavior, and validation are
**unchanged from v1** — do not re-specify them; follow `export-format-v1.md`.

### Task `assignee` (new, optional)

```jsonc
"tasks": [
  { /* all v1 task fields */, "assignee": null }
]
```

| Field | Type | Source | Notes |
|---|---|---|---|
| `assignee` | String? (UUID) | `backlog_task.assignee_user_id` | As of Phase 2 PR 3, export emits the **real** claimer's user id on shared boards (null when unassigned). |

Export MAY omit `assignee`; import treats absent as `null`. **Import always drops it** — a
cross-account user id is meaningless in the importing account, whose board is recreated fresh with
the importer as sole OWNER. So a round-trip through export/import unassigns every task by design.

## Export-side behavior (pre-board build)

Reshape `AccountService.exportAccount` from v1: keep `user`/`settings` as-is; move
`categories`/`tags`/`tasks` into a single `boards[0]` with `name = "My tasks"`, `role = "OWNER"`.
Task `title`/`description` are still decrypted under the **user** DEK here (no board DEK exists yet)
— the resulting JSON is plaintext, exactly as v1.

## Import-side behavior (board-aware build)

Mirror v1's `AccountImportService`, with the board layer added. For the **single** board in
`boards[]`:

1. `BoardService.createBoardForOwner(userId, board.name)` is effectively what the fresh account
   already has (the auto-seeded default board) — reuse it; do **not** create a second board.
2. Delete the auto-seeded default categories on that board (as v1 deletes the seeded categories).
3. Recreate `categories` → keep an old→new id map; `tags` → old→new map (as v1).
4. Recreate `tasks`: translate `categoryId`/`tagIds` through the maps; **encrypt `title`/
   `description` under the board DEK** (`boardCrypto.encrypt(boardId, …)`); `assignee` → null;
   `rescheduleCount = 0`, `lastScheduledInSessionId = null` (planning state doesn't survive, as v1).
5. `user`/`settings` exactly as v1 — encrypt personal fields under the **user** DEK;
   `emailVerifiedAt` stays null (re-verify on the new build).

Validation rules and the whole-import rollback-on-error behavior carry over verbatim from v1
(`export-format-v1.md` → "Validation rules" + "Import preconditions"), now evaluated per board.

## Import preconditions

Unchanged in spirit from v1, expressed against the board model: import is valid only on a
**freshly-registered account** whose only state is its **one auto-seeded default board** (default
categories, no tasks, no tags) and an empty settings row. Reject with 409 otherwise. Because a
fresh account already has exactly one board, import targets that board rather than creating one.

## Fields & tables explicitly NOT exported

Same as v1: LLM conversations/messages, planning sessions / planned tasks / slots, change events,
Spring Session rows. Additionally not exported (don't exist or are re-derived): `board_membership`
beyond the owner's own role, `board_data_key` (minted fresh on import), and any `assignee`.

## HTTP details

Identical to v1: `GET /api/v1/account/export`, session-cookie auth, `application/json`,
`Content-Disposition: attachment; filename="backlog-fyi-export-YYYY-MM-DD.json"`.
