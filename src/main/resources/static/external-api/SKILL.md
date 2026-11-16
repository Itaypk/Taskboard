---
name: backlog-fyi
description: >-
  Read and manage tasks in a Backlog.fyi backlog — search or list tasks, add a task, update one,
  mark it done, or archive it — and plan the user's week, putting tasks on their calendar. Use when
  the user asks about their backlog, task list, to-dos or weekly plan in Backlog.fyi, asks to
  capture a task there, or asks to plan their week with it.
---

# Backlog.fyi task API

Backlog.fyi is a weekly planner: a task backlog plus an AI planning conversation that blocks time
for agreed tasks on the user's calendar. This skill covers reading and editing tasks from outside
the app, and planning the week yourself: you work out the plan with the user, and Backlog shows it
and delivers it to their calendar.

## Setup

The user creates a token in the app under **Settings → Integrations**. It is shown once.

```bash
export BACKLOG_TOKEN=blf_...
export BACKLOG_URL=https://backlog.fyi
```

Every request sends `Authorization: Bearer $BACKLOG_TOKEN`. Scopes, each including the one before:
**read** (GET endpoints only), **write** (task changes) and **plan** (plan changes, which send
calendar invitations). If a call returns 403, its `detail` names the scope it needs; tell the user,
since the fix is a new token and you cannot widen an existing one.

A 401 means the token is missing, revoked or expired — the three are not distinguished. Don't
retry; tell the user their token stopped working and ask them to create a new one.

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
| `GET /api/external/v1/plans/week/{week}` | a week's plan and one-off events (`current`, `next` or a date) |
| `PUT /api/external/v1/plans/week/{week}` | replace a week's plan (**plan** scope) |
| `POST /api/external/v1/events/{id}/cancel` | cancel a one-off event (**plan** scope) |

### Dates and time zone

`deadline` and `relevantFrom` are plain `YYYY-MM-DD`, and the server hides future-dated tasks
relative to the **user's** time zone — not yours, and not UTC. Before turning "tomorrow" or "next
Friday" into a date, get the zone:

```bash
curl -sH "Authorization: Bearer $BACKLOG_TOKEN" "$BACKLOG_URL/api/external/v1/me"
# {"userId":"…","displayName":"Itay","timeZone":"Asia/Jerusalem",
#  "preferredLanguage":"en","defaultBoardId":"…","tokenScope":"write"}  # read | write | plan
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

### Planning the week

You do the planning conversation; Backlog stores the result and puts it on the user's calendar.
There is no draft: agree on the plan with the user first, then submit it once.

1. **Read the week.** `GET /plans/week/current` (or `next`, or the week's first day as
   `YYYY-MM-DD`) returns the existing plan (`plan` is null if there is none), the week's one-off
   events, the week's bounds, the user's `timeZone`, and `calendarDelivery`.
2. **Pick tasks** from `GET /tasks`, and agree on times with the user. Keep clear of the events.
3. **Submit the whole plan.** It replaces the week's plan: a planned task you leave out comes off
   the plan and its calendar invitation is cancelled. To change one slot, send everything back with
   that one slot changed.

```bash
curl -sX PUT "$BACKLOG_URL/api/external/v1/plans/week/next" \
  -H "Authorization: Bearer $BACKLOG_TOKEN" -H 'Content-Type: application/json' \
  -d '{"tasks":[{"taskId":"…","slots":[{"start":"2026-11-24T09:00+02:00","end":"2026-11-24T10:30+02:00"}]}],
       "note":"Short week: Thursday is a holiday."}'
```

- Slots are ISO-8601 date-times **with an offset**, in the user's time zone, starting inside the
  week. A task can have several slots; each lasts at most 24 hours.
- `note` is for what the schedule can't show (capacity, why something was deferred), not a recap.
- Done or archived tasks can stay in the plan unchanged, but can't be added or moved.
- Only the current week or a later one can be planned.
- A 409 means the user has a planning conversation open in the app for that week. Ask them to
  finish or abandon it there.
- Check `calendarDelivery` in the response: `email` sent invitations, `feed` updates their calendar
  subscription, and `none` means the plan stays in the app. Tell the user which.
- Planned tasks the user hid from the assistant aren't shown (`plan.hiddenTasksOmitted` counts
  them) and a submit keeps them as they are. You don't need to do anything about them.

## Working well with this API

- **Confirm before destructive or bulk writes.** Completing or archiving several tasks at once is
  worth a quick check with the user first. A single task they explicitly named is not. A plan
  submit sends calendar invitations, so show the user the plan and get their OK before sending it.
- **Search before creating**, when the user's phrasing suggests the task may already exist —
  duplicates in a backlog are worse than a redundant lookup.
- **Read errors.** Responses are RFC 7807 and the `detail` field says exactly what was wrong,
  including the allowed values for a bad enum. Correct and retry rather than guessing.
- **Respect `hiddenFromAssistant`.** Tasks carrying it are excluded by default; that flag is the
  user's explicit "not for the AI" marker. Do not set `includeHidden=true` unless they ask.
- **Rate limit** is 120 requests/minute. A 429 means slow down, not retry immediately.
- Task titles and descriptions are encrypted at rest and are the user's private content. Don't
  copy them anywhere the user hasn't asked for.
