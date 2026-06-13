# Account Export Format v1

This document specifies the JSON format produced by `GET /api/v1/account/export` and consumed by `POST /api/v1/account/import`. It is the source of truth that the export branch (off `main`) and the import branch (off the encryption branch) must converge on.

## Why this exists

The encryption migration wipes the production database. To preserve user data across the wipe we ship export changes on `main` first; users export their data; then we deploy the encryption + import code and users restore their data through `POST /account/import`.

There is **no backward compatibility** with the pre-v1 export. The new `formatVersion` field is the version tag; import refuses anything else.

## Version contract

- The top-level JSON object MUST include `"formatVersion": 1`.
- Import rejects requests with a missing or non-`1` `formatVersion` (HTTP 400).
- Any future format change bumps `formatVersion` (e.g. to 2) and either keeps v1 support or documents a separate migration path. Treat the schema below as frozen for v1.

## Top-level shape

```json
{
  "formatVersion": 1,
  "exportedAt": "2026-05-25T12:34:56.789Z",
  "user": { ... },
  "settings": { ... } | null,
  "categories": [ ... ],
  "tags": [ ... ],
  "tasks": [ ... ]
}
```

- `exportedAt` — ISO-8601 `Instant`. Informational only; import does not consult it.
- `settings` MAY be `null` for accounts that never persisted a settings row.

## User block

```json
"user": {
  "id": "<old-uuid>",
  "telegramUsername": "alice" | null,
  "telegramFirstName": "Alice" | null,
  "email": "alice@example.com" | null,
  "createdAt": "2026-01-01T00:00:00Z" | null
}
```

| Field | Type | Source on the export branch | Required? |
|---|---|---|---|
| `id` | String (UUID) | `UserEntity.id` | yes — informational, not used by import |
| `telegramUsername` | String? | `UserEntity.telegramUsername` | no |
| `telegramFirstName` | String? | `UserEntity.telegramFirstName` (plaintext on `main`) | no |
| `email` | String? | `UserEntity.email` (plaintext on `main`) | no — **NEW in v1** |
| `createdAt` | String? (ISO-8601) | `UserEntity.createdAt?.toString()` | no |

**Import behavior:** `telegramFirstName` and `email` are encrypted under the new account's DEK before persisting. `email_hash` is recomputed from a lowercase+trim of `email`. `emailVerifiedAt` is **NOT** restored — the user re-verifies via the existing flow. `id` and `createdAt` are ignored (new account, new timestamps).

Fields explicitly **NOT** exported (re-derived or transient on the new account):
- `telegramId` — comes from the next Telegram-login HMAC payload.
- `telegramPhotoUrl` — comes from the next Telegram-login HMAC payload.
- `emailVerifiedAt`, `emailVerificationToken*` — verification is redone on the new deployment.
- `isDemo`, `demoExpiresAt`, `lastLoginAt` — runtime/account-lifecycle metadata.

## Settings block

```json
"settings": {
  "displayName": "Alice" | null,
  "contextBlock": "I prefer deep work in the morning" | null,
  "timeZone": "Europe/London",
  "preferredLanguage": "en-US",
  "calendarInviteEmail": false,
  "gender": "feminine" | null,
  "agentDescription": "Founder working on X" | null,
  "planningCron": "0 30 9 * * MON" | null,
  "weekStartDay": "MONDAY" | null,
  "autoArchiveDays": 30 | null
}
```

| Field | Type | Source | New in v1? |
|---|---|---|---|
| `displayName` | String? | `UserSettingsEntity.displayName` (plaintext on `main`) | no |
| `contextBlock` | String? | `UserSettingsEntity.contextBlock` (plaintext on `main`) | no |
| `timeZone` | String | `UserSettingsEntity.timeZone` | no |
| `preferredLanguage` | String | `UserSettingsEntity.preferredLanguage` | no |
| `calendarInviteEmail` | Boolean | `UserSettingsEntity.calendarInviteEmail` | **yes** |
| `gender` | String? | `UserSettingsEntity.gender` | **yes** |
| `agentDescription` | String? | `UserSettingsEntity.agentDescription` (plaintext on `main`) | **yes** |
| `planningCron` | String? | `UserSettingsEntity.planningCron` | **yes** |
| `weekStartDay` | String? | `UserSettingsEntity.weekStartDay` | **yes** |
| `autoArchiveDays` | Int? | `UserSettingsEntity.autoArchiveDays` | **yes** |

**Import behavior:** validates `timeZone`, `preferredLanguage`, `gender`, `planningCron`, `weekStartDay` against the same enums/regex `UserSettingsService.update` uses today. On any invalid value the whole import rolls back with 400. `displayName`, `contextBlock`, `agentDescription` are encrypted under the new DEK.

## Categories array

```json
"categories": [
  { "id": "<old-uuid>", "label": "Work", "swatchId": "sunshine" },
  ...
]
```

| Field | Type | Source | Notes |
|---|---|---|---|
| `id` | String (UUID) | `BacklogTaskCategoryEntity.id` | used as a map key during import; new UUIDs are minted |
| `label` | String | `BacklogTaskCategoryEntity.label` | |
| `swatchId` | String | `BacklogTaskCategoryEntity.swatchId.name.lowercase()` | must parse as `CategoryColor` enum (`sunshine`, `blossom`, `mint`, `sky`, `lilac`, `peach`, …) |

**Import behavior:** import maintains an `oldId → newId` map. The auto-seeded default categories on the freshly-registered account are deleted before insertion so the user ends up with exactly the imported set.

## Tags array

```json
"tags": [
  { "id": "<old-uuid>", "label": "urgent", "colorId": "coral", "description": null },
  ...
]
```

| Field | Type | Source | Notes |
|---|---|---|---|
| `id` | String (UUID) | `BacklogTaskTagEntity.id` | map key during import |
| `label` | String | `BacklogTaskTagEntity.label` | |
| `colorId` | String | `BacklogTaskTagEntity.colorId.name.lowercase()` | must parse as `TagColor` enum |
| `description` | String? | `BacklogTaskTagEntity.description` | |

## Tasks array

```json
"tasks": [
  {
    "id": "<old-uuid>",
    "title": "Buy bread",
    "description": "From the place on 5th" | null,
    "url": "https://..." | null,
    "priority": "high" | null,
    "deadline": "2026-06-01" | null,
    "estimatedMinutes": 15 | null,
    "status": "todo",
    "categoryId": "<old-category-uuid>",
    "tagIds": ["<old-tag-uuid>", "..."],
    "sortKey": "a3f",
    "createdAt": "2026-05-01T12:00:00Z",
    "updatedAt": "2026-05-10T12:00:00Z" | null,
    "relevantFrom": "2026-05-20" | null
  },
  ...
]
```

| Field | Type | Source | New in v1? | Notes |
|---|---|---|---|---|
| `id` | String (UUID) | `BacklogTaskEntity.id` | no | informational; import mints new UUID |
| `title` | String | `BacklogTaskEntity.title` (plaintext on `main`) | no | required, non-blank |
| `description` | String? | `BacklogTaskEntity.description` (plaintext on `main`) | no | |
| `url` | String? | `BacklogTaskEntity.url` | no | |
| `priority` | String? | enum `low`/`medium`/`high` lowercased | no | |
| `deadline` | String? | `LocalDate.toString()` (`YYYY-MM-DD`) | no | |
| `estimatedMinutes` | Int? | direct | no | |
| `status` | String | enum `todo`/`done`/`archived` lowercased | no | |
| `categoryId` | String (UUID) | `BacklogTaskEntity.category.id` | no | required; must match one of `categories[*].id` |
| `tagIds` | List\<String\> | `BacklogTaskEntity.tags[*].id` | no | each must match one of `tags[*].id` |
| `sortKey` | String | `BacklogTaskEntity.sortKey` | no | preserved verbatim on import so list order matches |
| `createdAt` | String (ISO-8601) | `BacklogTaskEntity.createdAt.toString()` | no | |
| `updatedAt` | String? (ISO-8601) | `BacklogTaskEntity.updatedAt?.toString()` | no | |
| `relevantFrom` | String? (`YYYY-MM-DD`) | `BacklogTaskEntity.relevantFrom?.toString()` | **yes** | |

**Import behavior:** `title`/`description` encrypted under the new DEK. `categoryId` and `tagIds` translated through the old→new maps; an unknown reference fails the whole import (400). `rescheduleCount = 0` and `lastScheduledInSessionId = null` — planning-session state does not survive.

## Fields and tables explicitly NOT exported

These are out of scope for round-trip; users accept losing them:

- LLM conversations and messages (`ai_conversation`, `ai_message`) — transient per `ttl_days`.
- Planning sessions, planned tasks, task slots (`planning_session`, `planned_task`, `planned_task_slot`) — week-scoped; users re-plan.
- Backlog task change events (`backlog_task_change_event`) — transient inter-session diff data.
- Spring Session rows (`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`) — re-auth assigns these.

## Filename and HTTP details

- Endpoint: `GET /api/v1/account/export`.
- Authentication: standard session cookie (`@AuthenticationPrincipal TaskerPrincipal`).
- `Content-Type: application/json`.
- `Content-Disposition: attachment; filename="backlog-fyi-export-YYYY-MM-DD.json"`.

## Validation rules (mirrored on import)

| Field | Constraint |
|---|---|
| `formatVersion` | integer `1` |
| `tasks[*].title` | non-blank, max 500 chars |
| `tasks[*].description` | max 5000 chars |
| `tasks[*].url` | max 2000, matches `^$\|^https?://.*` |
| `tasks[*].priority` | parses as `TaskPriority` enum after `.uppercase()` |
| `tasks[*].deadline` / `tasks[*].relevantFrom` | matches `^\d{4}-\d{2}-\d{2}$` and parses as `LocalDate` |
| `tasks[*].status` | parses as `TaskStatus` enum |
| `tasks[*].categoryId` / `tasks[*].tagIds[*]` | must appear in this export's `categories`/`tags` |
| `categories[*].swatchId` | parses as `CategoryColor` (lowercased name) |
| `tags[*].colorId` | parses as `TagColor` (lowercased name) |
| `settings.timeZone` | one of `UserSettingsService.SUPPORTED_TIME_ZONES` |
| `settings.preferredLanguage` | one of `UserSettingsService.SUPPORTED_LANGUAGES[*].code` |
| `settings.gender` | one of `UserSettingsService.SUPPORTED_GENDERS[*].code` or null |
| `settings.planningCron` | `CronExpression.isValidExpression(...)` |
| `settings.weekStartDay` | parses as `java.time.DayOfWeek` |

Any validation failure rolls back the whole import (`@Transactional`) and returns HTTP 400 with the validation message.

## Import preconditions

Import refuses with HTTP 409 if the calling account has any of:
- one or more `BacklogTask` rows;
- one or more `BacklogTaskTag` rows;
- any `BacklogTaskCategory` row that doesn't exactly match (label + swatchId) one of the auto-seeded defaults in `UserService.DEFAULT_CATEGORIES`.

In other words, import is only valid on a freshly-registered account whose only state is the auto-seeded categories and an empty settings row.
