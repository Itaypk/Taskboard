# Quick add — capture tasks from Telegram (`/add`)

Status: implementation plan — no code yet.

## Why this improvement (and why first)

Of the three assistant ideas in [`IDEAS.md`](./IDEAS.md) — quick add, out-of-the-blue
responses, and the proactive task helper — quick add gives the most end-user value:

- **It closes the capture gap in the core loop.** `SPEC.md` step 1 is *Capture*, but today
  the only way to add a task is the web UI form. The "I just remembered a thing" moment
  happens on the phone, where Telegram — the product's conversational home — is already
  open. Quick capture is a *daily*-frequency feature; planning is weekly, and the proactive
  helper fires occasionally at best. Frequency × friction-removed puts it first.
- **It's the cheapest of the three by far, without sacrificing value.** Everything hard
  already exists and is proven in production: `TaskSuggestionAgent` drafts a complete task
  from free text, the `create_task` path persists a confirmed draft, `BotCommandDispatcher`
  already parses command args, and `PlanConfirmationRegistry` is the exact pattern for a
  multi-message command flow. What's genuinely new is small: a command handler, a tiny
  state machine, and a "revise this draft" variant of the suggestion prompt.
- **It matches what's been working.** We attribute the surprisingly good results from
  small/cheap models to focused prompts and tools with a small mistake surface. Quick add
  keeps that: every LLM call is a single-turn, single-purpose sub-agent call (draft /
  revise a draft). There is no open-ended conversation for the model to wander in.
- **It de-risks the other two ideas.** Out-of-the-blue texting is, in practice, mostly
  quick capture ("remind me to renew the passport") — phase 2 below carves off that slice
  cheaply, and the inbound-routing seam plus the draft-revision agent are exactly what a
  fuller implementation would reuse. The proactive helper needs outbound, non-planning
  interactions; the pending-interaction registry pattern here is a building block for that.

Why not the others first: out-of-the-blue responses require open-ended intent detection —
the largest possible mistake surface for a small model — for diffuse value. The proactive
helper is the most differentiating long-term, but it depends on accumulated signals
(`reschedule_count` only started getting bumped at plan finalization recently), risks
feeling noisy to a handful of beta users, and its value lands weeks after shipping.

## UX (Telegram, v1)

```
/add buy a birthday gift for mom by Friday
  → (typing…) draft card:
      ➕ Buy a birthday gift for mom
      Category: Personal · Priority: medium · Deadline: 2026-06-19
      Tags: errands
    [✅ Save]  [✏️ Adjust]  [✖️ Cancel]
```

- **`/add <text>`** — drafts immediately from the inline description.
- **`/add`** (no args) — asks "What's the task?" and treats the next message as the
  description.
- **Save** — persists the draft and confirms ("Added: *Buy a birthday gift for mom*").
- **Adjust** — asks "What should I change?"; the reply is folded into a revision call and
  the updated card re-renders. Loop until Save/Cancel.
- **Free text while the card is showing** is treated as an adjustment instruction directly
  (skipping the Adjust tap). This is what makes it *feel* like a conversation.
- **Cancel** — clears the flow, short ack.
- Any new `/command` while a quick-add flow is pending abandons the flow (same as the plan
  confirmation behavior: latest intent wins).

### Vague inputs: one bounded question, not a conversation

Some inputs can't be drafted without asking — a forwarded message with no context, "the
thing we discussed", an ambiguous category. Rather than guessing badly, the suggestion
agent may return a **clarification** instead of a draft:

```
/add (forwarded) "Saturday 14:00 works for them"
  → ❓ What is this about — is there a task here you'd like me to capture?
/add fix the door
  → ❓ Which category fits best?  [🏠 Home]  [🔧 Maintenance]  [💬 Let me explain]
```

- A clarification is either **open-ended** (rendered as text) or a **choice** (rendered
  with inline buttons, always including a "let me explain" escape that accepts free text).
- The answer is folded into the next suggestion call together with the original
  description and any prior Q&A; the result is a draft card (or, at most once more, a
  second question).
- **Hard cap: 2 clarification rounds per flow.** Past the cap the agent is instructed it
  must produce a best-guess draft — the adjust loop catches anything still off. This keeps
  the flow a flow, not a conversation.
- Ambiguous *adjustment* instructions go through the same mechanism.

### Deliberate design choice: a bounded conversation, not an agent

`IDEAS.md` notes the adjustment loop "will be a conversation". From the user's point of
view it is one — free text is accepted at every step. But the *orchestration* is a
deterministic state machine; the only model involvement is single-turn draft/revise calls
to `TaskSuggestionAgent`, which may at most surface a capped number of clarifying
questions as structured data the flow renders. No `say`/`ask_choice` toolset, no
model-driven turn-taking, no
new conversation transcript. If the loop turns out to be too rigid in practice, the inner
step can later be swapped for a mini `AiConversationManager` conversation with the existing
planning tool kinds — the seam (flow state keyed by chat) stays the same.

## Architecture

```
TelegramChannel.handleUpdate
   │  "/add …"            → BotCommandDispatcher → AddBotCommand
   │  text/callback while │
   │  a flow is pending   → QuickAddRegistry hit → QuickAddFlow.handleInbound
   ▼
QuickAddFlow  (channel-agnostic core, new)
   │  draft    → TaskSuggestionAgent.suggest(userId, description)
   │  revise   → TaskSuggestionAgent.revise(userId, previousDraft, instruction)   (new)
   │  save     → TaskDraft → CreateBacklogTaskRequest → BacklogTaskService.createTask
   ▼
ConversationChannel.send(Text | Choice)   — same rendering contract as planning
```

### State machine

Held in a new `QuickAddRegistry` (in-memory `ConcurrentHashMap<chatId, PendingQuickAdd>`,
mirroring `PlanConfirmationRegistry`):

```
AWAITING_DESCRIPTION                  (/add with no args)
  └─ text → suggest → ①
AWAITING_CONFIRMATION(draft)
  ├─ Save   → persist → confirm → cleared
  ├─ Cancel → cleared
  ├─ Adjust → AWAITING_ADJUSTMENT(draft)
  └─ free text → revise(draft, text) → ①
AWAITING_ADJUSTMENT(draft)
  └─ text → revise(draft, text) → ①
AWAITING_CLARIFICATION(question, exchange so far)
  └─ answer (text or selection) → suggest/revise with accumulated Q&A → ①

① every suggest/revise call resolves to either a draft → AWAITING_CONFIRMATION,
  or (while under the 2-round cap) a clarification → AWAITING_CLARIFICATION.
```

The flow enforces the clarification cap, not just the prompt: once the budget is spent,
the call is made in "must draft" mode, and a stray clarification coming back anyway is
treated like an unparseable response (graceful "let's try rephrasing" message).

- Entries carry a `createdAt` and are dropped when stale (~15 min TTL, checked on access)
  so an abandoned card doesn't swallow an unrelated message days later.
- In-memory only; lost on restart. Same trade-off as the planning orchestrator state and
  acceptable for a single prod instance.
- `QuickAddFlow` itself is written against `ConversationChannel` + explicit state values
  (no Telegram types), so a web entry point can reuse it later. The Telegram layer owns
  the registry and the chat-id keying.

### Routing changes in `TelegramChannel.handleUpdate`

Current precedence: command → pending plan confirmation → active planning session → help
fallback. Quick add slots in **after the plan-confirmation check and before the
planning-session check**:

1. Command branch: unchanged, except entering any command clears a pending quick-add flow.
2. `planConfirmationRegistry` check: unchanged.
3. **New:** `quickAddRegistry` check — both `Text` and `Selection` inbounds route to
   `QuickAddFlow.handleInbound`.
4. Active-session / help fallback: unchanged in v1 (the fallback copy should now mention
   `/add` alongside `/help`).

`/add` during an **active planning session** is refused with a redirect: "We're planning
right now — just tell me about the task here and I'll add it." The planning toolset
already has `suggest_task`/`create_task`, so the capability genuinely exists in-session;
no state juggling needed.

### Draft → persist (deterministic save path)

Mirrors `CreateTaskTool.execute` but without the model in the loop:

- Validate the draft before rendering the card: `category_id` must be one of the user's
  categories (else drop to the user's first/default category), `priority` must parse,
  `deadline` must be `YYYY-MM-DD`. Invalid optional fields are dropped silently — the user
  sees exactly what will be saved.
- Save maps `TaskDraft` → `CreateBacklogTaskRequest` (tags via `TagColorOptions.resolve`)
  and calls `backlogTaskService.createTask(userId, boardMembershipService.resolveDefaultBoard(userId), request)`.
  Default board only in v1 — consistent with the planner's temporary userId-only bridge
  (`BOARD-SHARING-PHASE1.md`); board pickers can come with planner board-awareness.

### Why a structured-output sub-agent, not tools

The planning conversation uses **tools**: the model emits `tool_calls` (`say`, `ask_choice`,
`create_task`, …) that `WeeklyPlanningOrchestrator` dispatches. Quick-add does **not**, and
that's deliberate. There are two LLM layers in this codebase:

- the **orchestrator layer** (`WeeklyPlanningOrchestrator` + `AiConversationManager` +
  `ToolRegistry`) — a multi-turn, tool-calling conversation; and
- the **sub-agent layer** (`TaskSuggestionAgent`, `BacklogTaskSearchAgent`) — single-shot
  calls that pass **no** tools and ask the model for one structured JSON object, parsed in
  Kotlin. Their prompts reference no tools (`task-suggestion/system.md`,
  `task-search/system.md`).

Quick-add's drafting *is* a sub-agent — the same `TaskSuggestionAgent` the in-session
`suggest_task` tool already wraps — so it belongs in the second layer. The `clarify` outcome
is therefore **a second variant of the sub-agent's structured response** (a discriminated
union `draft | clarify`), not a tool; and the turn-taking that would otherwise be a tool loop
is the deterministic `QuickAddFlow` state machine. This keeps the mistake surface small for a
cheap model (the whole point) and avoids dragging in conversation persistence and the
interactive-queue machinery for a 1–3 turn capture. The only thing tools would add here is
provider-side argument validation, which the deterministic save path (`toCreateBacklogTaskRequest`
+ field validation in `QuickAddFlow.validate`) covers. Converging onto real tools stays an
option (see "bounded conversation, not an agent" above) if that calculus changes.

### `TaskSuggestionAgent` extensions (the new LLM behavior)

Two additions, both single-turn calls with the same focused shape as today's `suggest`:

- **`revise(userId, previousDraft, instruction, …)`** — same system prompt family, new
  `prompts/task-suggestion/revise-user.md` template carrying the previous draft (as JSON)
  plus the user's adjustment instruction. Separate template rather than conditionals
  because `PromptTemplate` deliberately throws on missing variables.
- **Outcome contract: draft *or* clarification.** When invoked from the quick-add flow,
  the system prompt (a quick-add variant, e.g. `prompts/task-suggestion/system-clarify.md`)
  permits an alternative JSON output:
  `{"clarify": {"question": "…", "options": [{"id","label"}]?}}`. The Kotlin return type
  becomes a sealed `SuggestionOutcome` (`Draft(TaskDraft)` / `Clarify(question, options)`),
  and calls carry the accumulated Q&A exchange plus a `must draft` flag once the
  2-round budget is spent. The planning-session `suggest_task` tool keeps the current
  draft-only prompt and behavior in v1 — the planning assistant already handles vagueness
  conversationally on its own; unifying the two can come later if it earns its keep.

Temperature 0.3, same `AiConversationType.TASK_SUGGESTION` call context (a dedicated
`TASK_REVISION` type is optional if we want separate usage tracking). Clarification
questions are authored by the model in the user's preferred language (passed as a
template variable, as in the planning prompts).

### Localization & privacy

- All new copy goes through `MessageSource` — command description, "What's the task?",
  card field labels (Category/Priority/Deadline/Tags/Estimate), button labels, save/cancel
  confirmations, the in-session redirect — added to **all 13** `messages*.properties`
  bundles (Telegram stays fully localized). The draft *content* is in the user's own
  words/language by construction.
- No task titles or descriptions in logs; log flow transitions with userId only (MDC
  covers most of it).
- **Metrics (in scope):** a `tasker.quickadd.outcome{result=saved|cancelled|expired}`
  counter, matching the existing Prometheus counter pattern (`tasker.email.sent`). Cheap
  to add now, useful later; no Grafana board changes in this scope.

## Phases

- **Phase 1 (done):** `/add` end-to-end on Telegram — command, registry + flow state
  machine, draft card with Save/Adjust/Cancel, free-text adjustments, bounded
  clarifications, deterministic save, i18n bundles, tests.
- **Phase 2 (cheap follow-up, a slice of "out of the blue"):** bare free text with no
  active session/flow offers capture instead of the bare help reply — "Want me to add
  that as a task?" [Add it / No]. Reuses the entire phase-1 flow; the *only* new logic is
  the offer. Also a good home for a pre-save duplicate check via `BacklogTaskSearchAgent`
  ("this looks similar to *Renew passport* — add anyway?").
- **Phase 3 (later, optional):** web quick-add entry point reusing `QuickAddFlow` through
  a buffered channel (the `WEB-PLANNING.md` pattern); multimodal capture (voice/photo)
  per `IDEAS.md` if/when multimodal lands.

## Testing

- `QuickAddFlow` unit tests with mocked `TaskSuggestionAgent` + `BacklogTaskService`
  (MockitoExtension + mockito-kotlin, like `BacklogTaskServiceTest`): every transition,
  TTL expiry, invalid-draft fallbacks, save mapping, clarification round-trips (choice
  answer, free-text answer, escape option), cap enforcement (3rd clarification →
  graceful failure).
- `AddBotCommand` test: args vs. no-args, refusal during an active session.
- `TaskSuggestionAgent.revise` test with mocked `AiClient`: template assembly and
  `TaskDraft` parsing (happy path + unparseable output → null → graceful "didn't catch
  that, try rephrasing" message).
- Routing test for the new `TelegramChannel` precedence (pending quick-add vs. session).
- Manual: dev Telegram bot (`tasker.telegram.enabled=true` + dev bot token). A dev-console
  endpoint for the flow is possible later if Telegram-less testing is needed.

## Key files

New (as built):
- `capture/QuickAddFlow.kt` + `capture/QuickAddState.kt` (channel-agnostic core; `capture` package)
- `channel/telegram/QuickAddRegistry.kt` (chat-id keyed storage + idle TTL + `expired` metric)
- `channel/telegram/commands/AddBotCommand.kt`
- `resources/prompts/task-suggestion/system-clarify.md` (clarify-capable system prompt)
- `resources/prompts/task-suggestion/quickadd-user.md` (one user template covering the
  initial draft, clarification answers, and adjustments via conditional blocks)

Touched (as built):
- `channel/telegram/TelegramChannel.kt` (routing precedence + fallback copy)
- `planning/TaskSuggestionAgent.kt` (`quickAddDraft` / `quickAddRevise` returning the sealed
  `SuggestionOutcome`; `ClarifyOption` / `QaPair`)
- `ai/AssistantJson.kt` (`extractJsonObjectSpan` helper, shared with the new outcome parser)
- `resources/messages*.properties` (all 13 bundles)

Notes vs. the original plan: drafting and revision share one quick-add user template
(`quickadd-user.md`) rather than a separate `revise-user.md` — the previous draft and
adjustment instruction are just additional rendered blocks. The agent keeps a draft-only
`suggest` for the in-session `suggest_task` tool; the clarify-capable methods are quick-add
only, as planned.

Reused unchanged: `TaskSuggestionAgent.suggest`, `BacklogTaskService.createTask`,
`BoardMembershipService.resolveDefaultBoard`, `TagColorOptions`, `BotCommandDispatcher`,
`TelegramConversationChannel`.

## Resolved decisions

- **Mid-planning `/add`:** refuse and redirect into the session. The planning toolset
  already exposes the same capability (`suggest_task`/`create_task`), so there is no need
  to complicate routing just to allow it through `/add`.
- **One-tap save with a guessed category:** yes — save whatever the card shows; the
  adjust loop fixes mistakes. When the agent genuinely can't pick a category, that's
  exactly what the clarification mechanism is for (a category choice question), rather
  than a mandatory confirm step on every add.
- **Outcome metrics:** in scope. The counter is cheap to add now and useful later;
  dashboard updates are explicitly *not* part of this scope.
