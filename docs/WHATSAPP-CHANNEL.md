# WhatsApp as a conversation channel

Exploration note evaluating how Backlog.fyi could reach users over WhatsApp — quick-add capture,
the weekly planning conversation, and slot reminders — the way `TelegramChannel` does today.

Status: **exploration only** — feasibility, effort, and design tradeoffs, not a build plan. No
implementation should start from this document without a separate follow-up decision. Supersedes
the one-line idea at `docs/IDEAS.md` ("WhatsApp as a communication channel support.").

Hardware assumption behind the questions this note answers: a spare always-on Moto G5S Plus
(Android 8.1) with an Israeli SIM, fully under our control, while the app runs on a VPS in
Germany.

## Correcting the starting premise: the phone is not the asset

The natural worry — "the phone is in Israel, the server is in Germany, so a WhatsApp-Web-style
solution will lose access fast" — turns out not to be the binding constraint under either route,
though for opposite reasons:

- **Official (Cloud API):** the phone is *irrelevant*. A number registered on the WhatsApp
  Business Platform is migrated **off** the consumer app; the same number cannot run both. The
  SIM is needed exactly once, to receive the registration code. After that the number lives on
  Meta's servers and Backlog talks HTTPS to the Graph API. No device, no session, no geography.
- **Unofficial (linked-device libraries — Baileys / whatsmeow / WAHA):** linked devices connect
  to WhatsApp's servers **directly**; since multi-device shipped they do not relay through the
  phone. The primary phone only has to come online **once every 14 days** or all linked devices
  are logged out — which an always-on phone satisfies trivially. That is in fact the strongest
  argument for keeping the phone: it is the anchor that keeps the session alive.

What actually drives bans on the unofficial route is **behavioral fingerprint + datacenter IP**,
not the Israel/Germany distance. Reported signals: low reply ratio, messaging accounts far away
in the contact graph, robotic timing, and traffic originating from VPS/datacenter ranges. A
personal task bot scores *well* on the first three (every message is a reply to a user who
initiated) and badly on the fourth — which points at a different topology, see Option B.

**Android 8.1 is not a limiting factor.** WhatsApp's minimum rises to **Android 6.0 on
8 September 2026**; 8.1 stays supported. No need for a newer phone or a custom ROM.

## The constraint that actually shapes the work: the 24-hour window

This is what makes WhatsApp structurally different from Telegram, and it is the main effort
driver on the official route.

Telegram lets a bot message a user whenever it wants. WhatsApp does not. Outside a **24-hour
customer service window** (opened by the *user's* inbound message), a business may only send a
**pre-approved template message**; the user must reply before free-form conversation resumes.
Inside the window, free-form messages are **free and unlimited**.

Backlog's two most valuable channel behaviors are exactly the bot-initiated ones:

- the scheduled weekly planning conversation (`PlanningSessionScheduler` →
  `ScheduledConversationChannelResolver`, whose doc comment already anticipates "additional push
  channels … can slot in later"), and
- slot reminders (`SlotReminderDispatcher`), which push an interactive ack/snooze/done menu.

So a Cloud API adapter needs a state machine no existing channel needs: **template nudge → user
replies → 24h window open → run the real conversation**. If the user never replies, the session
never starts and must be retried or dropped. `SlotReminderDispatcher` already owns the
`PENDING`/`EXPIRED`/`SENT`/`FAILED` transitions, so there is a sane home for "nudged, window
never opened" — but the semantics are new.

**i18n multiplies this.** Templates are approved per language variant, and CLAUDE.md commits us
to four launched UI languages plus fully localized Telegram/email. Every proactive message is
four approved template variants, re-approved by Meta on every copy change — an ongoing tax on a
project whose current localization workflow is "edit a properties file."

**This is the one dimension where the unofficial route is functionally better**: no window, no
templates, no per-language approval, arbitrary proactive messages, and the existing
`MessageFormatter`/`ChannelMessage` shapes port almost unchanged. That tension — the official
route is operationally sound but constrained exactly where Backlog needs it most; the bridge is
functionally richer but operationally fragile — is the whole decision. **Unless we decide we do
not need the bot-initiated half at all**, which is the next section.

## The scope decision that changes the answer: reactive-only

Roughly 80% of the value here is **quick-add** — a user sends a line of text, a photo of a
whiteboard, or a voice note, and a task appears. That behavior is *purely reactive*: the user
always speaks first. It is worth treating "reactive-only" as a first-class scope choice rather
than as phase 1 of an inevitable march toward proactive, because it does not merely reduce the
work — it removes the entire class of work the previous section describes.

**What reactive-only buys.** If Backlog never initiates, every message we send is a reply inside
a window the user just opened, and therefore:

- **No message templates.** None to write, none to submit, none to get approved, none to
  re-approve when copy changes — and no ×4 language multiplier on any of that.
- **No template/window state machine.** The "nudge → hope they reply → then start the session"
  dance disappears, along with the new terminal state it needed ("nudged, window never opened").
- **Nothing to pay.** In-window free-form messages are free; templates were the only billable
  thing. The Israel/EU utility rate becomes a number we never have to look up.
- **No resolver or dispatcher work.** `ScheduledConversationChannelResolver` and
  `SlotReminderDispatcher` stay untouched — no two-channel `resolve`, no WhatsApp session
  registry, no both-linked precedence rule. That is a real chunk of the Option A estimate below,
  and the fiddliest chunk.
- **The linking design already fits.** The `wa.me/<botnumber>?text=LINK-<token>` flow described
  under Option A is inbound-first by construction, precisely because Tier 0 cannot send OTPs.
  Under reactive-only that stops being a workaround and is simply how the channel works.

**What it gives up — less than it sounds.** The losses are the *bot-initiated* behaviors: the
scheduled weekly-planning nudge, and slot reminders. But the planning **conversation** is not
lost — a user who messages "plan" gets the full session, in-window, with no template anywhere in
it. What goes is the nudge, not the feature. Slot reminders are the genuine casualty: a reminder
you have to ask for is not a reminder.

**And the nudge has a cross-channel escape hatch.** Backlog already reaches users by email, by
Telegram, and on the web. The weekly-planning email can carry a `wa.me/<botnumber>?text=plan`
link; tapping it sends the inbound message, opens the window, and the conversation then runs
entirely on WhatsApp. The push happens on a channel that permits pushing, and WhatsApp is used
only where it is unconstrained — no templates, no approvals, no Meta review of our copy. This
covers users with a verified email or a linked Telegram; a WhatsApp-only user under this scope
simply has a reactive-only product, which should be stated plainly at link time rather than
discovered.

**The one edge that survives.** Reactive-only never needs to *open* the window, but the window
still exists and can close underneath a session in progress. It is rolling — each user message
refreshes the 24 hours — so a quick-add exchange (seconds) or a planning conversation held in one
sitting is never at risk. A user who answers three planning questions and then walks away for a
day is: our next message is simply undeliverable. The honest handling is to treat that as an
abandoned session and let their next inbound message resume it, not to reach for a template.

**What this does to the option comparison — more than "points for A".** Options B and C exist
almost entirely to buy unconstrained proactive messaging; that is the one dimension on which
either beats the Cloud API. Remove the requirement and their advantage does not shrink, it
vanishes — while every cost stays exactly where it was: ToS exposure, ban risk, a bespoke Android
app or a second always-on box, user content in plaintext on the bridge, no interactive buttons,
no business profile. Under reactive-only, a consumer-account bridge pays full price for a benefit
no longer on the shopping list.

Two reasons would still put B or C back on the table under this scope, and both are narrow: Meta
refuses or never approves the business portfolio, or the one-time setup is judged not worth doing
at all. "Avoiding per-message cost" is explicitly *not* one of them — reactive-only has no
per-message cost.

---

## Option A — WhatsApp Business Platform (Cloud API), direct with Meta

**Verdict: recommended.** The setup paperwork is a one-time annoyance; a ban on the unofficial
route is a permanent, unappealable outage for every WhatsApp user at once. Read this section
together with the scope decision above: under reactive-only, everything below about templates,
the window state machine, and per-message cost falls away, and what remains is an adapter plus a
webhook.

The "non-trivial hassle" is smaller than its reputation at our scale:

- **No BSP needed.** Twilio/360dialog and friends resell the same Cloud API with a per-message
  markup. Going direct through Meta's self-serve signup avoids both the markup and a vendor.
- **No business verification needed at beta scale.** An unverified business portfolio (Tier 0)
  is capped at **250 unique recipients per rolling 24 hours** (since October 2025 these limits
  are scoped to the *business portfolio*, not the individual number), 250 templates, 2 numbers —
  orders of magnitude past "a handful of beta users." Verification matters only at growth, and
  it unlocks a 100k limit immediately once done.
- **Cost is near zero for us.** No subscription. Free-form messages inside the service window are
  never charged. Only proactive nudge/reminder templates are billable, as *utility* messages, at
  single-digit cents each in most markets — **verify the Israel and EU utility rates against
  Meta's current rate card before committing to a number.** At beta volume this is rounding-error
  money; it becomes a real line item only if reminders ever go high-frequency.
- **A dedicated number is required** and must not be in use on the consumer WhatsApp app. The
  spare SIM covers this; the phone itself is then surplus.

**Residual risk worth naming, not hand-waving: the general-purpose-AI-chatbot ban.** Meta's
WhatsApp Business Solution Terms prohibit distributing general-purpose LLM assistants on the
platform — effective 15 January 2026, and immediately for accounts registered on or after
15 October 2025. Backlog is a *scoped product assistant* (task capture, weekly planning) with a
bounded intent set (`TaskSuggestionAgent`'s `CaptureIntent` is a closed enum), not an open-domain
bot, so it sits on the permitted side of the line — Meta explicitly still encourages AI for
structured business tasks. But we would be a new registration under the strict regime, so: keep
the bot declining off-topic requests, describe it as a task manager rather than "an AI assistant
on WhatsApp," and treat Option B as the documented fallback if the portfolio is refused.

**Linking design — click-to-chat, not an SMS code.** The obvious flow (settings → enter your
number → we send you a code) is blocked: unverified Tier 0 accounts **cannot send OTPs over
WhatsApp**. Invert it instead — Settings shows a `wa.me/<botnumber>?text=LINK-<token>` deep link
(or QR); the user's *first inbound message* carries a single-use, short-TTL token minted with
`SecureRandom` and stored as a SHA-256 digest (the `ApiTokenService` pattern CLAUDE.md mandates,
**not** `UUID.randomUUID()`). This also opens the 24-hour window as a side effect. It mints an
`auth_identities` row with provider `whatsapp` and `provider_user_id` = the WhatsApp ID —
**no schema change** — and `AccountLinkService.linkTelegram` is the precedent to copy, including
re-publishing `UserPlanningScheduleChangedEvent` so a previously-skipped planning cron registers.

**What the adapter needs (mostly reuse):**

- `WhatsAppConversationChannel : ConversationChannel` — `supportsAutocompletions = false`;
  `supportsInlineButtons = true` but **capped at 3 reply buttons** (Meta's interactive-message
  limit; the interactive *list* variant allows 10 rows across all sections). The reminder menu
  (ack / snooze / done) fits the 3-button form natively; anything wider needs the list variant or
  the numbered-list degradation the `ConversationChannel` doc comment already anticipates.
- A webhook controller on a **new stateless filter chain**, analogous to `externalApiFilterChain`,
  verifying Meta's `X-Hub-Signature-256` HMAC and answering the `hub.challenge` verification GET.
  It must ack fast and process asynchronously — Meta retries on slow responses, so idempotency by
  message id is required.
- Media: Cloud API's two-step fetch (media id → signed URL → bytes) maps cleanly onto
  `TelegramMediaExtractor`'s shape and produces the same `InboundAttachment`; multimodal capture
  stays gated by `InputModalitySupport` exactly as today (see `docs/MULTIMODAL-CAPTURE.md`).
- The phone number is PII: store it encrypted under the user's DEK with a hash as the lookup
  handle — the `email_hash` + `UserCryptoService` pattern.
- `ScheduledConversationChannelResolver` currently hard-codes Telegram (`telegramChatId`) and is
  documented as the single place that assumption lives. It is more than a `when` over two
  channels: `hasDeliverableChannel` gates whether a scheduled session runs at all, and `resolve`
  returns an `onSessionStarted` hook that writes into `TelegramSessionRegistry` keyed by chat id
  — so WhatsApp needs its own registry keyed by WhatsApp ID. A user with **both** identities
  linked needs an explicit precedence rule (user preference, or "most recently used channel");
  that is a product decision, not a default to fall into.
- `AccountService.deleteUserData` must clear any new WhatsApp-identity table.
- Same `AiTier` / AI-opt-out gating as every other AI path, plus a rate-limit policy alongside
  the existing `quickAdd` one.

**Estimate:** the adapter itself is comparable to the web quick-add channel in
`docs/QUICKADD-CHANNELS.md` — moderate, heavily reusing `QuickAddFlow`, the planning
orchestrator, and `TaskDraftMapping`. The genuinely new work is (1) the template/window state
machine plus its four-language template lifecycle, and (2) one-time Meta portfolio setup. Split
it: **phase 1 = inbound-only quick-add** (the user messages the bot, we reply inside the window —
zero templates, zero approvals, real value); **phase 2 = proactive planning + reminders**, which
is where the template machinery lands. Phase 1 alone is worth shipping and de-risks everything
else.

## Option B — self-hosted linked-device bridge (WAHA / Baileys / whatsmeow)

**Verdict: viable as a personal-scale experiment or a fallback, not as the channel we onboard
beta users onto — and under a reactive-only scope, hard to justify at all**, since it would carry
the ban risk to buy a capability we had decided not to want.

WAHA is the pragmatic packaging — a Dockerized REST + webhook gateway over several engines
(browser-based `WEBJS`/`WPP`, socket-based `NOWEB` (Baileys) / `GOWS` (whatsmeow)) — so we'd
write an HTTP adapter rather than maintain a reverse-engineered protocol. Functionally it is a
superset of Telegram: no window, no templates, proactive messages whenever we like.

The cost is that it violates WhatsApp's ToS and the ban risk is real and unpredictable —
including reports of "your account may be at risk" warnings, and bans, for *low-volume,
reply-only* clients. A ban removes the number permanently, with no appeal path, and takes every
user on it down at once.

**If it is tried, invert the topology instead of running it on the German VPS.** Run WAHA on
hardware sharing the phone's own Israeli residential network — a Pi/mini-PC, or Termux on the
Moto itself — and have it make **outbound** HTTPS calls to Backlog (a long-poll/queue relay, or
an authenticated push to an `/api/external`-shaped endpoint). Nothing inbound is exposed, the
German VPS never touches WhatsApp, and the datacenter-IP signal disappears. That directly answers
the original geography concern, and it is the only configuration worth considering here. It does
add a second always-on box on a home connection as a hard dependency of the channel.

**Honest scope note:** the ToS violation is our own account and number, at personal scale. That
is a defensible choice for a solo experiment; putting beta users' planning conversations behind
it is a different thing. Hence the recommendation split.

## Option C — dedicated-device bridge on the real WhatsApp client

**Verdict: a real option, and the low ban risk is genuine.** An earlier draft of this note
dismissed it in one line on the assumption that it meant scraping notifications and tapping
coordinates. Worked through properly it is a sounder design than that, and it is the only route
that gets Telegram-grade proactive messaging *and* near-zero ban exposure. It loses on build
cost, product polish, and an operational surface we'd own forever — see the decision axis in the
Recommendation. Note that reactive-only removes its entire reason to exist: everything C is
expensive for is the proactive half.

**Why the ban risk really is near zero.** Every packet WhatsApp sees comes from an unmodified
client, on a real device, on a residential Israeli IP, with the SIM that registered the number.
There is no linked-device session, no reverse-engineered protocol, and no datacenter traffic —
none of the signals from Option B apply. What remains is behavioral (volume, timing), and a
personal task bot is well-behaved on those by construction. This is a ToS matter, not a
detection matter: automating the consumer client is still outside WhatsApp's terms, the same as
Option B, but the practical chance of being caught is not comparable.

**The design, and why ADB and Tailscale drop out of it.** The natural starting proposal is
"notification tells us something arrived, then ADB over Tailscale fetches the content and sends
the reply." That works, but once you accept a small app on the device, ADB stops earning its
place — and ADB is the part with the awkward prerequisites (on Android 8.1 there is no wireless
debugging pairing, so `adb tcpip` needs a USB host on every boot or root to make
`persist.adb.tcp.port` stick, and it means `adbd` listening on the tailnet plus a disabled lock
screen so `input` works). A sideloaded app is both simpler and more robust:

- **Inbound text — notification extras, not scraped text.** A `NotificationListenerService` reads
  `EXTRA_TEXT` / the `MessagingStyle` messages directly. Truncation is a *rendering* concern; the
  extra carries the full body and the sender. No root, no UI, no polling.
- **Inbound media — the filesystem.** Android 8.1 predates scoped storage, so
  `/sdcard/WhatsApp/Media/…` is plainly readable; auto-download is on, so the bytes are already
  there when the notification fires. Being stuck on 8.1 is an *advantage* here.
- **Outbound replies — `RemoteInput`.** The same direct-reply mechanism smartwatches use:
  `Notification.Action.getRemoteInputs()` → `RemoteInput.addResultsToIntent()` → fire the
  `actionIntent`. No UI, no screen-on, no coordinates, fast enough that the serialization worry
  about routing every user through one device largely evaporates. Do not cancel the notification
  before replying — cancelling closes the reply channel.
- **Transport — the app calls out.** An outbound HTTPS long-poll (or an authenticated push to an
  `/api/external`-shaped endpoint) means nothing inbound is exposed and no VPN is needed.
  Tailscale becomes a convenience for administering the phone, not part of the data path.

**The catch, and it lands exactly on C's main selling point.** `RemoteInput` can only *reply* to
a conversation that has a live notification. Opening a conversation — which is precisely the
proactive weekly-planning nudge that C exists to make possible — has no notification to reply to,
and falls back to `am start` on a `wa.me/<number>?text=…` deep link plus an Enter keypress. That
is a deep link addressed by phone number rather than a coordinate tap, so it is far steadier than
blind automation and it is low-frequency (once per conversation), but it is UI automation, it
needs the screen awake, and it is on the critical path for the one capability C is being
considered for. Address it with a closed loop rather than hope: after sending, confirm the
message actually appears before treating it as delivered, and make the send idempotent so a retry
can't double-post.

**Root is optional, and one claim needs checking before anyone builds on it.** The design above
needs no root. Root buys a reconciliation safety net: reading
`/data/data/com.whatsapp/databases/msgstore.db` gives complete history, so a message that never
produced a notification (muted chat, odd device state) can still be picked up. The mainstream
forensic position is that the *live* database there is plain SQLite and that the `key` file
alongside it decrypts the `.crypt14` **backups**, not the live db — but sources conflict on
whether the live db is itself encrypted on current builds, so treat "can `sqlite3` open
`msgstore.db` on this device" as an experiment to run, not a fact to design around. The
notification path does not depend on the answer.

**Other things this route owns:**
- **Product polish is worse.** Users message a personal mobile number: no business profile, no
  verified name, no interactive buttons at all (`supportsInlineButtons = false`, so every
  `ChannelMessage.Choice` degrades to a numbered list, including the reminder ack/snooze/done
  menu). Option A gets all of that for free.
- **A second thing to keep alive.** A years-online phone on a home connection, plugged in
  permanently — swelling batteries on a 2017 handset are a real and boring failure mode — plus a
  sideloaded app that breaks whenever WhatsApp changes its notification shape.
- **Android 6.0 floor.** 8.1 is fine until at least September 2026, but the device is on borrowed
  time. If it gets unlocked for root anyway, LineageOS on this handset (`sanders`) reaches
  Android 11+, which extends its life and brings proper wireless debugging — at the cost of the
  easy pre-scoped-storage media path above.

**Estimate:** the Kotlin side of the app is small and well-trodden (a notification listener, a
`RemoteInput` sender, a foreground service, an HTTPS client) — but it is a **new artifact in a
new deployment target**, with its own build, signing, sideloading, and update story, none of
which the current Ansible/VPS setup covers. Call it comparable to Option A's adapter in raw code,
and larger than it in total ownership.

## Non-options (recorded so they aren't re-explored)

- **On-Premises WhatsApp Business API** — sunset by Meta; Cloud API is the only official path.
- **Blind UI automation** — driving WhatsApp by screen coordinates (`input tap x y` against a
  scraped layout). Every WhatsApp release can move a button, and the failure mode is not a clean
  error but a tap landing somewhere else — a task sent into the wrong chat. The *structured*
  version of a device bridge is a real option and is now **Option C** below; it is only the
  coordinate-tapping form that is rejected.
- **DMA third-party chat interoperability** (EU): requires signing a provider agreement with Meta
  as a messaging service, is EU-only, and needs each WhatsApp user to opt in. First partners went
  live in late 2025. Not accessible to a solo side project.

## Recommendation

1. **Settle the scope question before the transport question.** Reactive-only vs. proactive
   decides the size of this project far more than which of A/B/C is chosen — and on the current
   read (quick-add is most of the value), reactive-only is the right target. Adopting it as the
   *scope*, not merely as phase 1, is what lets the template machinery be genuinely absent rather
   than deferred.
2. **Go direct to Cloud API, reactive-only.** A dedicated number on the spare SIM, an unverified
   Tier 0 portfolio, a webhook, and free in-window replies. Comparable in size to the web
   quick-add channel in `docs/QUICKADD-CHANNELS.md` plus a webhook, with no template approvals,
   no per-message cost, and no ban exposure.
3. **If a nudge is wanted, try the cross-channel one first.** A `wa.me/<botnumber>?text=plan` link
   in the weekly-planning email costs one line and no Meta review. Only if that measurably fails
   should native templates — and their four-language approval lifecycle — be reconsidered as a
   separate, explicitly justified project.
4. **Keep a consumer-account bridge (B or C) as the fallback**, if the Meta portfolio is refused
   or the AI-chatbot policy is read against us. Note that "template overhead" stops being a reason
   to reach for one once reactive-only is the scope. If B, run it on the phone's own network,
   never on the VPS.

**Choosing between B and C, if it comes to that** — and under reactive-only it likely never does
— **hinges on how replaceable the number is.** Both
are ToS-grey consumer-account bridges and both hold user content in plaintext on the bridge —
that is a property of *any* consumer-account route, not a discriminator between them. What
differs is the price of a ban. Right now the number is a spare SIM in a spare phone and there
are a handful of beta users, so a ban costs a re-registration and a few re-links — which makes
B's fragility genuinely *cheaper* than C's build cost, despite B being the riskier design. C only
pays for itself in a narrow band: proactive messaging on a consumer number that has become a
published identity users have saved. By the time the number is worth that much, the right answer
is Option A. C is therefore best understood as insurance, not as a destination.

Product caveat: WhatsApp's value here is **reach** (users who won't install Telegram), not
capability — on capability it is strictly worse than what we already have. Worth weighing against
the web quick-add channel in `docs/QUICKADD-CHANNELS.md`, which is cheaper and serves every
existing user.

## Verification

This document has no code to verify. If phase 1 is greenlit, the first checkpoint is entirely
outside the app: a Meta business portfolio + WABA exists, the number is registered, and the
webhook verification GET succeeds against a tunnelled local endpoint. Only then is
application-level work meaningful — after which it follows the existing pattern: drive
`QuickAddFlow` through the new channel the way `DevQuickAddController` /
`static/dev-quickadd.html` already do, then confirm end-to-end from a real WhatsApp client
(message the bot → draft card → save → task appears on the default board).

## Sources

- [Meta — WhatsApp messaging limits](https://developers.facebook.com/documentation/business-messaging/whatsapp/messaging-limits)
- [WhatsApp Business Platform pricing](https://whatsappbusiness.com/products/platform-pricing/)
- [TechCrunch — WhatsApp bars general-purpose chatbots](https://techcrunch.com/2025/10/18/whatssapp-changes-its-terms-to-bar-general-purpose-chatbots-from-its-platform/)
- [respond.io — not all chatbots are banned](https://respond.io/blog/whatsapp-general-purpose-chatbots-ban)
- [WABetaInfo — Android support ending September 2026](https://wabetainfo.com/whatsapp-to-drop-support-for-older-android-versions-in-september-2026/)
- [WhatsApp Help — linking a second phone (14-day rule)](https://faq.whatsapp.com/1046791737425017/)
- [WAHA](https://github.com/devlikeapro/waha) · [whatsmeow issue #810 — "account may be at risk"](https://github.com/tulir/whatsmeow/issues/810)
- [Android — `RemoteInput` direct reply](https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action) · [`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Group-IB — WhatsApp forensic artifacts](https://www.group-ib.com/blog/whatsapp-forensic-artifacts/) (live `msgstore.db` vs. `.crypt14` backups; conflicting accounts exist — verify on device)
- [Tailscale Android](https://tailscale.com/docs/install/android) (Android 8.0+)
- [Meta — messaging interoperability in Europe](https://about.fb.com/news/2025/11/messaging-interoperability-whatsapp-enables-third-party-chats-for-users-in-europe/)
