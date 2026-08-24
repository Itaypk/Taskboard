---
name: backlog-fyi
description: >-
  Read and manage tasks in a Backlog.fyi backlog — search or list tasks, add a task, update one,
  mark it done, or archive it. Use when the user asks about their backlog, task list, or to-dos in
  Backlog.fyi, or asks to capture a task there.
---

# Backlog.fyi task API

Backlog.fyi is a weekly planner: a task backlog plus an AI planning conversation that blocks time
for agreed tasks on the user's calendar. This skill covers the backlog half — reading and editing
tasks from outside the app.

## Setup

The user creates a token in the app under **Settings → Integrations**. It is shown once.

```bash
export BACKLOG_TOKEN=blf_...
export BACKLOG_URL=https://backlog.fyi
```

Every request sends `Authorization: Bearer $BACKLOG_TOKEN`. A **read**-scoped token may call GET
endpoints only; writes need a **write**-scoped token. If a write returns 403, say so — the fix is
a new token, and you cannot widen an existing one.

The full contract is at `/external-api/openapi.yaml` if you need exact schemas.

## The six things worth knowing

1. **Use `PATCH`, never reconstruct a task.** `PATCH` changes only the fields you send. Do not
   read a task, modify it, and send the whole thing back — you will not need to, and the app's
   own `PUT` endpoint (which you should not use) *is* a full replace that wipes omitted fields.
2. **Use `POST /complete` to mark something done**, not `PATCH {"status": "done"}`. It is
   idempotent and runs the same completion side effects the app does.
3. **Unsetting a field is explicit.** `{"deadline": null}` is read as "leave it alone". To remove
   it, send `{"clear": ["deadline"]}`.
4. **Board and category are optional on create.** Omit them and the task lands on the user's
   default board in its first category. Only call `/boards` and `/categories` when the user names
   a specific one.
5. **There is no delete.** `POST /archive` is the reversible equivalent. Archiving is a normal
   action; if the user genuinely wants a task gone forever, tell them to do it in the app.
6. **Resolve relative dates against the user's time zone**, from `GET /me` — not your own clock.

## Endpoints

| | |
|---|---|
| `GET /api/external/v1/me` | who the token acts as, and the user's time zone |
| `GET /api/external/v1/tasks` | search + list |
| `GET /api/external/v1/tasks/{id}` | fetch one |
| `POST /api/external/v1/tasks` | create |
| `PATCH /api/external/v1/tasks/{id}` | partial update |
| `POST /api/external/v1/tasks/{id}/complete` | mark done |
| `POST /api/external/v1/tasks/{id}/archive` | archive |
| `GET /api/external/v1/boards` | boards; the first is the default |
| `GET /api/external/v1/categories` | categories (`?board=` to narrow) |
| `GET /api/external/v1/tags` | tags (`?board=` to narrow) |

### Dates and time zone

`deadline` and `relevantFrom` are plain `YYYY-MM-DD`, and the server hides future-dated tasks
relative to the **user's** time zone — not yours, and not UTC. Before turning "tomorrow" or "next
Friday" into a date, get the zone:

```bash
curl -sH "Authorization: Bearer $BACKLOG_TOKEN" "$BACKLOG_URL/api/external/v1/me"
# {"userId":"…","displayName":"Itay","timeZone":"Asia/Jerusalem",
#  "preferredLanguage":"en","defaultBoardId":"…","tokenScope":"write"}
```

`/me` is also the cheapest way to check a token works and whether it may write.

### Searching

`GET /api/external/v1/tasks` spans every board the user belongs to.

| Param | Notes |
|---|---|
| `q` | case-insensitive substring of title or description |
| `status` | `todo` (default), `done`, `archived`, `all` |
| `tag` | exact tag label, case-insensitive |
| `priority` | `low`, `medium`, `high` |
| `board` | restrict to one board |
| `limit` / `offset` | default 50, max 200 |

The response is `{"tasks": [...], "count": N, "truncated": bool}`. If `truncated` is true, narrow
the query rather than paging blindly through everything.

```bash
curl -sH "Authorization: Bearer $BACKLOG_TOKEN" \
  "$BACKLOG_URL/api/external/v1/tasks?q=invoice&status=todo"
```

### Creating

Only `title` is required.

```bash
curl -sX POST "$BACKLOG_URL/api/external/v1/tasks" \
  -H "Authorization: Bearer $BACKLOG_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Renew passport","priority":"high","deadline":"2026-03-01","tags":["errands"]}'
```

`tags` are plain labels. An existing tag is matched case-insensitively; an unknown one is created.
Optional fields: `description`, `url`, `estimatedMinutes`, `relevantFrom` (a date before which the
task stays hidden from the todo list), `boardId`, `categoryId`, `hiddenFromAssistant`.

Dates are `YYYY-MM-DD`.

### Updating

```bash
# Raise priority. Description, tags, deadline are untouched.
curl -sX PATCH "$BACKLOG_URL/api/external/v1/tasks/$ID" \
  -H "Authorization: Bearer $BACKLOG_TOKEN" -H 'Content-Type: application/json' \
  -d '{"priority":"high"}'

# Remove the deadline.
curl -sX PATCH "$BACKLOG_URL/api/external/v1/tasks/$ID" \
  -H "Authorization: Bearer $BACKLOG_TOKEN" -H 'Content-Type: application/json' \
  -d '{"clear":["deadline"]}'
```

`tags` is the one field that replaces rather than merges: sending `{"tags":["a"]}` leaves the task
with exactly one tag. Omit it to leave tags alone; `{"clear":["tags"]}` removes them all.

Clearable: `description`, `url`, `priority`, `deadline`, `estimatedMinutes`, `relevantFrom`, `tags`.

### Completing

```bash
curl -sX POST "$BACKLOG_URL/api/external/v1/tasks/$ID/complete" \
  -H "Authorization: Bearer $BACKLOG_TOKEN"
```

## Working well with this API

- **Confirm before destructive or bulk writes.** Completing or archiving several tasks at once is
  worth a quick check with the user first. A single task they explicitly named is not.
- **Search before creating**, when the user's phrasing suggests the task may already exist —
  duplicates in a backlog are worse than a redundant lookup.
- **Read errors.** Responses are RFC 7807 and the `detail` field says exactly what was wrong,
  including the allowed values for a bad enum. Correct and retry rather than guessing.
- **Respect `hiddenFromAssistant`.** Tasks carrying it are excluded by default; that flag is the
  user's explicit "not for the AI" marker. Do not set `includeHidden=true` unless they ask.
- **Rate limit** is 120 requests/minute. A 429 means slow down, not retry immediately.
- Task titles and descriptions are encrypted at rest and are the user's private content. Don't
  copy them anywhere the user hasn't asked for.
