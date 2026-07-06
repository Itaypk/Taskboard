# Account Export Format v3

This specifies the JSON produced by `GET /api/v1/account/export` and consumed by
`POST /api/v1/account/import`. It supersedes `docs/archive/export-format-v2.md`. Read v2 first — **v3 is
v2 with internal database ids removed from the wire format**, plus a stricter email-import rule.
Per-field semantics defer to v2 (and through it, v1) wherever unchanged.

## What changed from v2

v2 leaked internal database identifiers that import never actually needed as identity:

- `user.id` — exported but never read on import (the importer's own session `userId` is used).
- `categories[*].id`, `tags[*].id`, `tasks[*].id` — real DB UUIDs, used only as join keys to wire
  a task to its category/tags.
- `tasks[*].assignee` — a cross-account user id that import always dropped anyway.

v3 removes all of these. Tasks now reference their category and tags by **position** in the
board's `categories` / `tags` arrays:

- `tasks[*].categoryId` (UUID string) → `tasks[*].categoryIndex` (integer, 0-based).
- `tasks[*].tagIds` (UUID strings) → `tasks[*].tagIndexes` (integers, 0-based).

There is no backward compatibility with v2. `formatVersion` is the version tag; import refuses
anything that isn't `3` (HTTP 400).

## Top-level shape

```jsonc
{
  "formatVersion": 3,
  "exportedAt": "2026-06-28T12:34:56.789Z",
  "user":     { "telegramUsername": "…"|null, "telegramFirstName": "…"|null,
                "email": "…"|null, "createdAt": "…"|null },   // no "id"
  "settings": { ... } | null,                                  // unchanged from v2
  "boards": [
    {
      "name": "My tasks",
      "role": "OWNER",
      "categories": [ { "label": "Work", "swatchId": "sunshine" } ],          // no "id"
      "tags":       [ { "label": "urgent", "colorId": "coral", "description": "…"|null } ], // no "id"
      "tasks":      [ { /* …, */ "categoryIndex": 0, "tagIndexes": [0, 1] } ]  // no "id"/"assignee"
    }
  ]
}
```

`settings` is unchanged from v2. `user` loses only its `id`.

## Index references

- `categoryIndex` MUST be a valid 0-based position into the same board's `categories` array.
  Import rejects an out-of-range index with HTTP 400.
- `tagIndexes` is a list of 0-based positions into the board's `tags` array; each must be valid.
- Export emits categories/tags in a stable order and writes task indices against that order, so a
  round-trip preserves every task's category and tags.

## Email handling on import (stricter than v2)

Email is **identity, not portable content**: it backs the unique `email_hash` handle and a login
method. v2 unconditionally wrote the exported email onto the importing account, which broke when
importing one account's export into another (it tried to claim an `email_hash` another live row
already held → unique-constraint 500). v3 only adopts the exported email when it is safe:

| Condition | Behavior | Reported in summary |
|---|---|---|
| No email in export, or it already equals the account's email | nothing to do | — |
| Account already has a *different* email | keep the account's own email | `emailSkipReason: "ACCOUNT_HAS_EMAIL"` |
| Exported email belongs to **another** account | keep the account's own email | `emailSkipReason: "TAKEN"` |
| Otherwise (fresh account, address free) | adopt the exported email | `emailImported: true` |

As in v2, no `auth_identities` row is created and `emailVerifiedAt` stays null — a re-verification
turns an adopted email into a login method.

## Import summary

`POST /import` returns:

```jsonc
{ "categories": 6, "tags": 3, "tasks": 13, "emailImported": false, "emailSkipReason": "TAKEN" }
```

`categories`/`tags`/`tasks` are aggregate counts across all imported boards (unchanged from v2).
`emailImported` / `emailSkipReason` report the email outcome above. The frontend shows the result —
counts and any email note — in a modal dialog, on both success and failure.

## Tutorial tasks

The seeded **tutorial** backlog (`backlog_task.tutorial = true`) is product onboarding, not the
user's own data:

- **Export excludes it** — only real tasks are emitted.
- **Import ignores and clears it** — an account that still has its tutorial tasks (but no real
  tasks) counts as "fresh" and is importable; import deletes the tutorial tasks before writing the
  imported content onto the default board.

## Import preconditions & everything else

Import preconditions (fresh account — only the seeded default board, default categories, no real
tasks, no tags; 409 otherwise), the per-board wipe-and-rebuild, board
DEK re-encryption of task `title`/`description`, dropped fields (LLM data, planning state, session
rows), and whole-import rollback-on-error are all **unchanged from v2** — see
`docs/archive/export-format-v2.md`.

A unique-constraint violation that somehow slips past the email guard is mapped to HTTP 409 with a
generic message (no raw DB error, which can carry another user's email hash), not a 500.
