# Free-text messages to the bot

Design note for turning the assistant's out-of-the-blue reply into something useful: a message sent
to the Telegram bot with no `/command` and no conversation in progress becomes a quick-add capture,
with a bounded escape hatch for messages that aren't captures at all.

Status: **proposed**. Nothing here has shipped.

## Why

Today, a message that arrives outside a planning session, outside a quick-add, and without a
leading `/` hits the dead end at the bottom of `TelegramChannel.handleUpdate`:

```
Send /add to capture a task, or /help to see what I can do.
```

That costs us twice:

- **The capture we asked for is one word away.** Forwarding a message to the bot, or typing the
  thing you just remembered, is the single highest-value interaction the product has — and we
  reject it because it lacks a four-character prefix. People who forward things to each other on
  Telegram don't prefix them.
- **It's the same reply for every kind of message.** "Call the plumber tomorrow", "mark the taxes
  task as done", and "hi" all get the same string, so none of them get anywhere.

## What we're building

Unprompted text in a **private chat** opens a quick-add capture, exactly as if the user had typed
`/add <that text>` — same draft card, same Save / Adjust / Cancel, same clarify loop.

The capture agent gains a third reply shape for the case where the message isn't a capture at all.
When it fires, the bot says so plainly and offers the commands instead of inventing a task.

After a capture is saved, and **only** under narrow conditions (D6), the bot offers to put the new
task into this week's plan — through the same deterministic, LLM-free path the web UI's
"Add to this week's plan" context-menu action uses.

## Decisions

### D1. The classification rides inside the existing capture call, not in front of it

The obvious design is an intent-router: one model call to classify the message, then dispatch to
`/add`, `/plan`, `/current`, `/stats`. We're not doing that. `TaskSuggestionAgent.quickAddDraft` is
*already* a model call over free text, and it already returns one of two shapes (`items` /
`clarify`). A router in front of it pays for a second call, on every message, to answer a question
the first call is positioned to answer as a side effect of the work it's already doing.

So the classification becomes a third reply shape from the same call:

```json
{"not_a_capture": {"intent": "plan|current|stats|help|unclear"}}
```

Modelled as `SuggestionOutcome.NotACapture(intent)` alongside `Draft`, `Clarify` and `Unparseable`.
Cost and latency for the common case (a real capture) are unchanged — the routing is free.

The destination set is small and closed (`add`, `plan`, `current`, `stats`, `help`), and three of
those are cheap read-only commands, so a dedicated router agent would be over-built for the job
even setting the second call aside.

### D2. `not_a_capture` is offered only on the unprompted entry call

This is the important scoping rule. The third shape is rendered into the prompt **only** for a
capture that started from an unprompted message. It is not offered:

- when the user typed `/add …` — they said explicitly what they wanted;
- on any *subsequent* call within a live capture — a revise, a clarification answer, an adjustment.
  Once the draft card is on screen the user has committed to capturing something; "no, not like
  that" there means *adjust the draft*, not *re-route to another command*. Letting the model bail
  out mid-flow would turn a fixable draft into a dead end.

Mechanically this is a flag threaded like the existing `must_draft_block`: a boolean on
`quickAddDraft` that controls whether the `not_a_capture` clause is rendered into
`system-clarify.md`. `QuickAddFlow.begin` gets an `unprompted: Boolean` parameter, false everywhere
today, true from the new routing path only; `PendingOp` does not carry it, so it cannot leak into
the revise/clarify calls.

Consequence worth stating: the shipped `/add` behavior is **bit-identical** after this change. All
new behavior sits behind a flag that only the new entry point sets, which keeps the blast radius of
a prompt regression to the new path.

### D3. Read-only intents run; expensive ones ask

What the router does with each `intent`:

| intent | action |
| --- | --- |
| `current`, `stats`, `help` | run the handler directly |
| `plan` | a confirmation button — "Start weekly planning?" |
| `unclear` | a short "here's what I can do" message with the command buttons |

Running `current` / `stats` / `help` outright is the delightful outcome and the risk is negligible:
they're read-only, cheap, and a misclassification costs the user one glance and one tap. `/plan` is
different — it opens a multi-turn LLM conversation and can supersede an existing session, so it
gets a confirm.

`unclear` is the "hi!" / "asdsdas" / "i don't like apple sauce" bucket, and it doesn't need to be
clever. One message naming what the bot does, plus the buttons, is already strictly better than
today's static string.

### D4. Plain capture alone isn't enough, and the reason is in the prompt

Worth recording why the simple version (route everything to quick-add, keep the existing exits) was
rejected. `system-clarify.md` says *"Strongly prefer capturing… A reasonable best guess beats an
unnecessary question"*, and `SuggestionOutcome.Unparseable` only fires on malformed JSON or an
empty item list. So "mark the taxes task as done" does not fall through to a graceful failure — the
model cheerfully drafts a task **titled** "Mark the taxes task as done", the user taps Save out of
momentum, and now there's a junk row in the backlog to clean up.

That failure is the default, not an edge case, and it's what D1 exists to prevent.

### D5. Private chats only, and rate-limited

Two gates that are latent today and become load-bearing the moment free text costs a model call:

- **Private chats only.** `handleUpdate` never inspects the chat type — it reads `message.chatId`
  and `message.from.id` and proceeds. Today a bot sitting in a group answers stray group messages
  with a static string, which is merely noisy. Under this change it would run a *paid capture on
  every group message*, saving tasks into whichever member's Telegram id is linked. The new path
  gates on `message.chat.isUserChat`; group chats keep the existing static reply.
- **A per-user capture limit.** `tasker.rate-limit` is enforced by `RateLimitInterceptor`, an HTTP
  `HandlerInterceptor` — nothing on the Telegram path passes through it, and `AiAccessService` is
  an opt-out check, not a throttle. Typing `/add` is the current de-facto throttle; removing it
  makes every stray message billable. Add a `tasker.rate-limit.quickadd` bucket using the existing
  `RateLimiter` (same shape as `FeedbackService`), checked before the model call, with a plain
  "you've hit today's capture limit" reply.

### D6. The plan hand-off reuses the deterministic web path, and is gated hard

There is already an LLM-free way to put a task into the current plan — the web UI's context-menu
"Add to this week's plan":

```
POST /api/v1/plans/current/tasks/{taskId}  { startIso, endIso }
  → PlanFinalizationService.addTaskToSession(userId, sessionId, AgreedPlanTask(...))
```

which upserts the planned task, stamps the backlog task with the session, bumps the plan watermark,
dispatches the calendar invite, and syncs the slot reminder. The Telegram offer calls the same
service — no planning conversation, no revision session, no model call.

**Gating.** The offer appears only when *all* of:

1. `planningSessionService.findCurrentPlan(userId) != null` — a finalized plan exists for the
   current week (same precondition as the web UI's `currentPlan !== null`);
2. the capture produced **exactly one task** (no events, no multi-item captures — an offer per item
   is noise);
3. **and** the intent is clear from the capture itself, by either:
   - the drafted task's `deadline` falls inside `[weekStart, weekStart + 6]` — a cheap, deterministic
     condition computed from the draft we already have; or
   - the user said so, detected by the capture model as a top-level `"plan_this_week": true` on its
     reply ("add it to this week's plan", "I want to do this Tuesday").

Outside those conditions the offer is suppressed entirely. Most captures are backlog items that are
explicitly *not* for this week; asking every time is exactly the useless noise this gate exists to
avoid.

**The slot problem.** `addTaskToSession` requires a concrete `startIso`/`endIso` — the web UI gets
them from `ScheduleTaskModal` (day grid + time input). Telegram has buttons. The proposal:

- when the user named a time, let the capture model propose the slot (it already resolves relative
  dates and offsets for events) and offer a single confirm button;
- otherwise a two-tap picker: remaining days of the week, then a coarse time (morning / afternoon /
  evening → 09:00 / 14:00 / 19:00), with duration = `estimatedMinutes ?? 30`.

The picker is the baseline because it always works; the proposed-slot path is the optimization on
top. Both end at the same `addTaskToSession` call, and both must surface the invite-not-sent case
the way `quickadd.event.invite_skipped` already does.

### D7. Unprompted media keeps the existing behavior, for now

`docs/MULTIMODAL-CAPTURE.md` D8 says an attachment must land *inside* a quick-add, and a photo that
arrives on its own gets a pointer to `/add`. Its stated reason was that out-of-band attachment
behavior was an open product question — and for *text*, this note answers it.

We're deliberately not extending the answer to media in this change. Bytes cost more than tokens, a
forwarded image is far likelier to be incidental than a typed sentence, and keeping the change
text-only means one prompt shape and one routing branch to reason about. Revisit once we have
capture-rate numbers for unprompted text.

## Not doing

- **A separate intent-router agent** (an extra classification call in front of quick-add). D1 — the
  cost is real, the benefit is already available inside the call we're making.
- **A conversational "coach" persona** for stray messages. Far more surface than the problem
  justifies, and it competes with the planning conversation for the same ground.
- **Unprompted photos and voice notes.** D7.
- **Post-hoc "add to plan" for events or multi-item captures.** D6 gate 2.

## Open questions

- Does the capture model separate `plan` from `current` reliably enough to auto-run `current`? If
  the confusion matrix is bad in practice, collapse both to a confirm button.
- Should an unprompted message during a **planning session** stay with the orchestrator? Yes today —
  the session check runs before the new branch, and `quickadd.in_session` already covers the case.
  Noted so the ordering in `handleUpdate` doesn't drift.
- The `unclear` reply is a good place to teach the feature ("just send me a task, no command
  needed"), but it's also the reply a confused user sees most. Worth a copy pass.

## Metrics

Extend the existing counters rather than adding a family:

- `tasker.quickadd.outcome{result}` — new values `not_a_capture`, `rate_limited`.
- a new `tasker.quickadd.entry{source}` with `command` / `unprompted`, so we can see whether
  unprompted captures actually materialize and whether their save rate differs from `/add`'s.
- `tasker.quickadd.route{intent}` for the `not_a_capture` distribution — this is what tells us
  whether D3's auto-run split is set correctly.

## Key components

- `channel/telegram/TelegramChannel.kt` — the routing change; the private-chat gate; replaces the
  static dead-end reply.
- `capture/QuickAddFlow.kt` — `unprompted` entry parameter; `NotACapture` rendering; the plan-offer
  gate and its new states.
- `capture/QuickAddState.kt` — a state for the plan offer / slot picker.
- `planning/TaskSuggestionAgent.kt` — `SuggestionOutcome.NotACapture`, `plan_this_week` parsing.
- `resources/prompts/task-suggestion/system-clarify.md` + `quickadd-user.md` — the third reply
  shape, rendered conditionally.
- `planning/PlanFinalizationService.kt` — reused unchanged.
- `ratelimit/` + `config/RateLimitProperties.kt` — the new capture bucket.
- `resources/messages*.properties` — new keys in all 13 bundles.

## Testing

- `QuickAddFlow` unit tests: `NotACapture` renders the right thing per intent; the flag is off for
  `/add` and for every mid-flow call (D2); the plan offer appears only under all three gates and is
  suppressed for events, multi-item captures, and out-of-week deadlines.
- `TelegramChannel` tests: a group-chat message never reaches the capture path; an unprompted
  private message does; a message during a planning session still goes to the orchestrator.
- Rate-limit test: the N+1th unprompted message in a window gets the limit reply and makes no model
  call.
- Plan hand-off: the picker produces a slot that `addTaskToSession` accepts, and the invite-skipped
  branch is surfaced.

## Phases

1. **Routing + `not_a_capture`.** D1–D5. This is the whole user-visible win and is independently
   shippable.
2. **Plan hand-off.** D6, behind its gates, once phase 1's numbers show unprompted captures are
   actually being saved.
