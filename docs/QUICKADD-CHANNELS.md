# Additional quick-add channels: web UI and inbound email

Exploration note evaluating two more entry points into quick-add capture beyond Telegram: a
web UI quick-add, and an inbound email address users can send/forward mail to.

Status: **exploration only** — feasibility, effort, and design tradeoffs, not a build plan.
No implementation should start from this document without a separate follow-up decision.
Supersedes the earlier one-line idea ("Incoming email address, as an additional way to quick-add
tasks"), now tracked as issue #267.

## Why this is easier than it looks

Quick-add (turning a short piece of text/photo/voice into a task via AI) currently only works
through Telegram. But the feature was already built channel-agnostic on purpose:
`QuickAddFlow` (`capture/QuickAddFlow.kt`) has **zero Telegram imports** — it depends only on
the `ConversationChannel`/`ChannelInbound`/`ChannelMessage` abstraction
(`channel/ConversationChannel.kt`), whose own doc comment says "Telegram today, email/web/etc.
tomorrow." A dev-only HTTP harness already proves this end-to-end without Telegram:
`DevQuickAddController` (`@Profile("dev")`) drives the exact same flow via
`BufferedConversationChannel`, with a working manual test page at
`static/dev-quickadd.html`. This materially changes the shape of both estimates below: web is
close to "finish what's started," email's hard part is entirely on the *receiving* side, not
the AI/task-creation side.

All three existing task-creation paths in the app already converge on one persistence call —
`BacklogTaskService.createTask(userId, boardId, request)` — via a shared mapper,
`TaskDraft.toCreateBacklogTaskRequest()` (`planning/TaskDraftMapping.kt`), explicitly
documented as shared between the planner's `create_task` tool and Telegram quick-add. Neither
new channel needs any new persistence-layer work.

---

## Channel 1: Web UI quick-add

**Verdict: low effort, high confidence.** This is largely wiring up what already exists behind
the `dev` profile, following the precedent `WebPlanningController` already set for exposing a
Telegram-shaped AI flow to signed-in web users.

**What to build:**
- A session-authenticated controller mirroring `WebPlanningController`'s shape (auth + CSRF,
  not the dev harness's unauthenticated per-profile map), backed by
  `BufferedConversationChannel`.
- A small React entry point mirroring `WeeklyPlanDrawer.tsx`'s chat pattern (single-turn draft
  card → Save/Adjust/Cancel, occasionally a clarify prompt) — much simpler than the planning
  drawer since quick-add is a bounded state machine, not an open conversation.
- Everything else — `QuickAddFlow`, `TaskSuggestionAgent`, the prompt templates, the
  Save/Adjust/Cancel card shape, media support — is reused unchanged.

**Decisions this needs (not blockers, just need an explicit answer before building):**
- **Board targeting**: the manual web form is already board-scoped via the URL
  (`/boards/{boardId}/tasks`), so a web quick-add should target the **currently open board**,
  not `boardMembershipService.resolveDefaultBoard(userId)` the way Telegram and the planner
  tool do. This is a real divergence from the existing AI paths and worth deciding explicitly
  rather than defaulting silently.
- **The `not_a_capture` intent leak**: the capture model's third reply shape routes to a closed
  set of Telegram bot-command names (`plan|current|stats|help|unclear`,
  `TaskSuggestionAgent.kt` `CaptureIntent`). This only fires when the flow is invoked as
  *unprompted* (ambient free text with no explicit "add" action). A web quick-add opened by an
  explicit button click should call `QuickAddFlow.begin(...)` (the prompted path, same as
  Telegram's `/add`), not `beginUnprompted(...)` — that sidesteps the issue entirely for v1, no
  prompt/intent remapping needed.
- **Session persistence**: `WebPlanningController` flagged in-memory session loss on server
  restart / page reload as an open gap (409 on reload, "deferred to phase 2b"). That gap is
  much less costly here — a quick-add session lives seconds (one draft, maybe one adjust), not
  a 20-turn planning conversation — so an in-memory registry is a reasonable v1 choice, not a
  blocker.
- **AI tier cap / opt-out**: must honor `AiTier` token accounting and per-user AI opt-out the
  same way `TelegramChannel` checks before calling the model, or this becomes an unmetered path
  against the OpenRouter budget.

**Estimate:** small — on the order of the size of the existing dev harness plus a thin
production controller and a compact React component. No new persistence, no new AI logic, no
new infra.

---

## Channel 2: Inbound email quick-add

**Correcting the starting premise:** the Protonmail SMTP token we already use
(`TASKER_EMAIL_AUTH_*` / `TASKER_EMAIL_SCHEDULING_*`) authenticates **outbound submission
only**. Proton has no inbound webhook or mail-receiving API — receiving mail from Proton
requires **Protonmail Bridge**, a desktop app that proxies one mailbox to a local IMAP/SMTP
endpoint. Bridge is not a server-side pipeline; running it headless as a VPS sidecar is
non-standard and its availability depends on the Proton plan. Confirmed in this codebase:
there is currently **no inbound mail code of any kind** — no IMAP client, no MIME parser, no
mail-receiving webhook, no reply-to-invite handling (calendar invites are one-way, per
`docs/NOTIFICATIONS.md`: "Email-only. Invites only reach users with a verified email").
`jakarta.mail`'s `Store`/`Folder` API is on the classpath (pulled in transitively by
`spring-boot-starter-mail`) but nothing in the app uses it. This is greenfield.

**Receiving mechanism — self-hosted receive-only MX (chosen over a third-party inbound-parse
vendor or Protonmail Bridge).** Run Postfix on the VPS for a dedicated subdomain (e.g.
`add.backlog.fyi`), with no mailboxes and no SMTP-auth outbound — it only accepts inbound SMTP
for that subdomain and hands each message to the app (HTTP callback or LMTP). This avoids a
new vendor dependency and fits the existing Ansible + Nginx ops repo (the separate Ansible repo) rather
than introducing a Mailgun/Postmark-style account. The tradeoff, now owned by us instead of a
vendor: SPF/DKIM verification and spam filtering (rspamd/opendkim, or an equivalent
lightweight check) are our responsibility, not handed to us as a pre-verified webhook payload.
This is new production infrastructure — DNS (MX record), a new service on the VPS, and a way
to get parsed mail into the Spring app — which is a meaningfully larger ops lift than the web
channel, and worth scoping as its own small project alongside the application-level work
below.

**Flow design — recommend fire-and-forget, not a Telegram-style back-and-forth.** Mirroring
Telegram's clarify/adjust/confirm loop over email is the single biggest cost driver if chosen,
and email's asynchronous, buttonless nature is a poor fit for it anyway (a "confirm" step means
waiting on a reply that might come minutes or days later, requires parsing free-text replies
including trailing quoted history, and needs per-thread state). Recommended shape instead:
- Always produce a best-effort draft and **create the task immediately** — no confirmation
  round-trip.
- Send back a confirmation email naming what was created, with a link into the web UI to
  correct it if the parse was wrong.
- Corrections happen in the web UI (reusing the manual edit form), not by replying to the
  email.

This eliminates the most expensive and most fragile pieces: an email-specific `QuickAddState`
registry, `Message-ID`/`In-Reply-To` thread correlation, clarify-round handling, the
`not_a_capture` routing, and reply-quote-stripping (client-dependent and genuinely messy —
different mail clients quote trailing history differently). If true interactive back-and-forth
is wanted later, it can be layered on; it should not be the v1 target.

**Sender authentication — recommend per-user secret inbound address, not `From:`-header
trust.** SMTP `From:` is trivially spoofable and shouldn't be treated as identity. Instead,
mint each user a unique, secret inbound address (or address-local-part token) the same way
`ApiTokenService` already mints external API tokens: `SecureRandom`-generated secret, only a
SHA-256 digest stored (CLAUDE.md is explicit that this is the correct pattern and that
`UUID.randomUUID()`, used elsewhere for tokens, is not). The address *is* the bearer
credential — revocable and rotatable from settings, blast radius bounded to creating tasks on
one board, matching the External API's existing isolation philosophy. Caveat worth stating
plainly: an inbound address is lower-assurance than a typical bearer token because it can leak
into a correspondent's Sent folder or autocomplete — acceptable here given the bounded blast
radius, not acceptable if this pattern were reused for something more sensitive. SPF/DKIM
verification (now our own responsibility per the MX choice above) should be treated as spam
reduction, not as the identity mechanism.

**Other net-new work, once receiving + auth + flow are settled:**
- MIME parsing: subject/body → title/description; forwarded mail (`Fwd:`) is email's genuinely
  distinctive use case (can't do this via Telegram or web) and deserves explicit design
  attention since it's a different shape than a short typed note.
- HTML-only bodies need HTML→text conversion — **no HTML-parsing library (e.g. jsoup) is
  currently a dependency** (checked `build.gradle.kts`), so this is a new dependency, not
  reuse.
- Attachments are close to free: `InboundAttachment`/`AttachmentKind` and the media-capture
  model path are already channel-agnostic (built for exactly this per
  `docs/MULTIMODAL-CAPTURE.md`), so MIME attachment extraction just needs to produce that same
  shape. Still gated by `InputModalitySupport`'s fail-closed check — stays dark unless
  `TASKER_AI_MULTIMODAL_MODEL` is configured with a model advertising the right modalities.
- Mail-loop protection: check `Auto-Submitted:`/`Precedence:` headers before sending a
  confirmation, never reply to bounces.
- A new rate-limit policy alongside the existing `quickAdd` (60/hr) policy — per-sender-address
  **and** per-source, following the precedent in `docs/LAUNCH-PLAN.md` where a per-address-only
  limit let one host mail unlimited distinct strangers.
- `AccountService.deleteUserData` must clear the new per-user inbound-address table —
  CLAUDE.md calls this pattern out explicitly and it's an easy thing to forget.
- Same AI tier cap / opt-out gating as the web channel.

**Estimate:** materially larger than the web channel, and dominated by the receiving
infrastructure rather than the application logic:
- Self-hosted MX + DNS + spam/SPF/DKIM handling + getting parsed mail into the app: a discrete
  infra sub-project on top of the existing Ansible/Nginx repo — not huge, but real ops work
  with its own failure modes (deliverability, spam, a new internet-facing service).
- Application-side work for the fire-and-forget flow (parsing, per-user address auth, rate
  limiting, confirmation email, cleanup wiring): comparable in size to, or somewhat larger
  than, the web channel's total effort, once receiving is in place.
- A full interactive (Telegram-mirroring) flow instead of fire-and-forget would add
  materially more — thread correlation, an email-specific state registry, quote-stripping —
  and is not recommended as the v1 target.

---

## Recommendation

- **Build the web quick-add first.** It's close to free given the existing channel-agnostic
  architecture and the working dev harness, and it closes a real gap: right now AI-assisted
  capture is Telegram-only for signed-in web users outside the planning drawer.
- **Treat email as a separate, larger project**, scoped around the self-hosted MX
  infrastructure work plus a deliberately simplified fire-and-forget flow (not a port of
  Telegram's interactive loop). Its unique value — forwarding existing mail into tasks, and
  reaching users who won't create a Telegram account — is real but narrower than the web
  channel's, and it's the only one of the two carrying new production infrastructure.

## Verification (once either is greenlit)

This document has no code to verify. If the web channel is picked up next, verification would
follow the existing pattern: exercise the new controller against `QuickAddFlow` the way
`DevQuickAddController`/`dev-quickadd.html` already does, then confirm in the browser (create →
draft card → save → task appears on the open board). If email proceeds, verification starts at
the infra layer (MX resolves, a test message reaches the app) before any application-level
testing is meaningful.
