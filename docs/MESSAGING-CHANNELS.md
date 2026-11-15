# Messaging quick-add channels: WhatsApp (and, deferred, Webex)

Implementation plan for WhatsApp (official Cloud API) as a quick-add source, and for the shared
layer that the next chat channel (Slack, Teams, …) would reuse. A Cisco Webex bot is designed
here too, but **deferred, possibly for good** (see "Why Webex is deferred").

Status: **plan, not built.** Written to be handed to an implementation agent. It builds on the
exploration in `docs/WHATSAPP-CHANNEL.md` (issue #268) and takes its recommendation as settled:
official API, reactive-only. Where this plan and that note disagree, this plan wins (see
"Corrections to earlier notes").

## Scope

In:

- A linked user messages the bot (text, photo, voice note, PDF, text file) and gets the same
  quick-add capture Telegram has: draft card, Save / Adjust / Cancel, clarifying questions, and the
  "put it in this week's plan" offer. All of that is `QuickAddFlow`, unchanged.
- Linking and unlinking a WhatsApp account from Settings.

Out, on purpose:

- **Anything the bot would have to start.** No weekly planning nudge, slot reminders or daily
  digest on these channels. On WhatsApp this is what avoids message templates entirely; on Webex
  it is simply not needed yet. `ScheduledConversationChannelResolver`, the reminder dispatcher
  and the digest stay Telegram-only and untouched.
- **The planning conversation and the bot commands** (`/plan`, `/current`, `/stats`). A message
  that the capture model classifies as one of those gets a short reply naming what this channel
  does and pointing at the web app.
- **Login.** A WhatsApp or Webex link is not a login method.
- **Group conversations.** Direct (1:1) chats only.
- **Migrating Telegram** onto the new shared layer. Possible later; not part of this work.

## Why Webex is deferred

Webex is only ever a work account, and a capture sends the message to the AI provider. Most
enterprise AI-usage policies forbid exactly that, so the people who could use the channel are
mostly not allowed to. Its design (section 6) and the platform research stay in this document so
the work is not repeated if that changes, but nothing in the WhatsApp path depends on it, and no
Webex code, configuration or UI should be built now.

## Current design, as it affects this work

| Piece | State | Consequence |
|---|---|---|
| `ConversationChannel`, `ChannelMessage`, `ChannelInbound`, `InboundAttachment` (`channel/ConversationChannel.kt`) | Channel-agnostic | Reused as is. Each new channel is one more adapter. |
| `QuickAddFlow`, `QuickAddState`, `CaptureEntry` (`capture/`) | Channel-agnostic; no Telegram imports; rate limit, validation and metrics inside | Reused as is. The caller only stores the returned state and handles `CaptureEntry.Routed`. |
| `TelegramChannel.handleUpdate` | The inbound **router** (link lookup, AI gate, media, state lookup, unprompted capture) is written inline against Telegram types, mixed with commands, planning, reminders and digest | Not reusable. The capture-only subset (about 80 lines of logic) is re-expressed once in a shared service, below. |
| `QuickAddRegistry`, `TelegramSessionRegistry`, `PlanConfirmationRegistry`, `BotCommandContext` | Keyed by Telegram `chatId: Long`, `@ConditionalOnTelegramBot` | Not reusable. A generic registry is added; these stay. |
| `TelegramMediaExtractor` | Size caps, attachment classification and audio-format mapping are private to it | The policy part is extracted and shared; the Telegram download stays. |
| Identity | `users.telegram_id` + an `auth_identities` row. Telegram is both a login method and a channel | New channels need a separate link model, below. |
| Inbound transport | Telegram: long polling, no public endpoint. Email: `POST /internal/inbound-mail` from a local MTA, loopback only, persistent queue | Neither is a public webhook. That is new: first unauthenticated, internet-facing write endpoints. |

## Verified platform facts

Checked against Meta's and Cisco's documentation in November 2026. Items marked **(verify)** could
not be confirmed from a primary source (developer.webex.com renders client-side) and must be
checked against the live API before the code relies on them.

### WhatsApp Cloud API

- **Webhook-based: yes.** One callback URL per Meta app. Setup handshake is a `GET` with
  `hub.mode=subscribe`, `hub.verify_token`, `hub.challenge`; answer 200 with the challenge as the
  body when the token matches.
- Each `POST` carries `X-Hub-Signature-256: sha256=<hex>`, an HMAC-SHA256 of the body under the
  **app secret**. Verify over the raw bytes, before parsing.
- Anything but a 200 is a failure. Meta retries at once, then with decreasing frequency for
  **up to 7 days**, and tells integrators to de-duplicate. One POST can batch several updates.
  Payloads are up to 3 MB.
- A message sits at `entry[].changes[].value.messages[]`: `id` (the dedup key), `timestamp`,
  `type`, `from` (phone number), `from_user_id` (BSUID). Button and list taps arrive as
  `type=interactive` with `button_reply.id` / `list_reply.id`. The same webhook also delivers
  `statuses[]` (sent / delivered / read) for our own messages: ignore them.
- **Business-scoped user IDs (BSUID)** are in every webhook since April 2026, and Meta says to
  identify users by them. Once a user adopts a WhatsApp username, `from` / `wa_id` can be
  **absent**. Replies can be addressed with `recipient: <BSUID>` instead of `to: <phone>`. A BSUID
  is scoped to the business portfolio: moving the number to another portfolio invalidates links.
- Media: `GET /<version>/<media-id>` returns a URL (valid 5 minutes), then `GET` that URL, both
  with the bearer token. Media ids from webhooks live 7 days. Voice notes are OGG/Opus. Caps:
  image 5 MB, audio 16 MB, document 100 MB (ours are lower, see below).
- Reply buttons: **at most 3**, title ≤ 20 characters, body ≤ 1024. List message: ≤ 10 rows,
  row title ≤ 24, body ≤ 4096. Text message: ≤ 4096.
- Typing indicator: one call with the inbound message id; it also marks the message read and
  lasts 25 seconds or until we reply.
- Free-form replies are allowed only within 24 hours of the user's last message. Since we only
  ever reply, we are always inside it.
- **Cost.** Replies are "service" messages: 1,000 free per number per month, **billed beyond
  that since 1 October 2026**. Negligible at beta volume, but it is the reason replies to unknown
  senders are capped (below).
- Examples in the docs use Graph API `v27.0`.
- **(verify, owner)** Meta's "AI providers" clause bars general-purpose assistants. A scoped
  task-capture bot is on the permitted side by every reading found, but the policy has changed
  several times in 2026. Read the current WhatsApp Business Solution Terms before registering,
  and describe the bot as a task manager.

### Webex

- **Webhook-based: yes** for a server-side bot. Webhooks are not configured in a console: the
  bot **registers them through the API** (`POST /v1/webhooks` with `targetUrl`, `resource`,
  `event`, `secret`, optional `filter`).
- `X-Spark-Signature` is a hex HMAC-**SHA1** of the raw body under the webhook's `secret`. Some
  webhook types also send `X-Webex-Signature` (SHA-256/512), which Cisco says to prefer when
  present; its format is in the API changelog **(verify)**.
- **The payload has no message text.** `messages:created` carries ids only (`data.id`,
  `data.personId`, `data.personEmail`, `data.roomType`); fetch `GET /v1/messages/{id}` with the
  bot token.
- A tap on a card button arrives on a second webhook, `attachmentActions:created`; fetch
  `GET /v1/attachment/actions/{id}` for the submitted `inputs`.
- Buttons are Adaptive Cards (`Action.Submit`). A card message must also carry `text` or
  `markdown` as fallback.
- **(verify)** The bot receives a webhook for its own messages (drop where `data.personId` is
  the bot's id); in group spaces it only sees messages that mention it; files are listed as URLs
  on the message, downloaded with the bot token, and may answer `423 Locked` with `Retry-After`
  while being scanned; messages accept Markdown, ≤ 7439 bytes; cards up to schema 1.3; there is
  no typing indicator; failed deliveries are **not retried**, and a webhook that keeps failing
  is set to `inactive`; rate limiting is `429` with `Retry-After`.
- An organization's admin can block bots, or external bots. Nothing we can do; the link
  instructions should say so.

## Design

### 1. One shared capture router: `MessagingCaptureService`

New package `dev.itayp.tasker.channel.messaging`. Everything provider-independent lives here;
a provider contributes a webhook controller, an API client and a `ConversationChannel`.

A provider's controller turns a webhook into zero or more:

```kotlin
data class InboundEvent(
    val channel: ChannelType,          // WHATSAPP
    val eventId: String,               // provider message / action id, for dedup
    val senderId: String,              // BSUID, Webex personId
    val content: Content,              // Text | Selection(optionId) | MediaRef(caption)
    val reply: ConversationChannel,    // built by the provider, addressed to the sender
    val fetchMedia: (() -> MediaFetch)?,   // lazy: runs on the worker, after the gates
)
```

and calls `MessagingCaptureService.accept(event)`, which returns immediately. Processing runs on
a virtual thread and does, in order:

1. **De-duplicate** on `(channel, eventId)`: an in-memory set with a 24-hour TTL. In memory is
   enough because we acknowledge before we process, so a retry means "the acknowledgement was
   lost or late", which only happens while this instance is up. (Same single-instance assumption
   as issue #259.)
2. **Serialize per conversation**: events of one `(channel, senderId)` run one at a time, in
   arrival order. Use `KeyedSerialExecutor` (`channel` package), which Telegram already uses to
   order updates per chat (issue #382). A capture takes seconds; without this, two
   messages sent in a burst race on the stored state.
3. **Resolve the link** to a user (section 3). Unknown sender: if the text is a link code,
   consume it and confirm; otherwise reply once with how to connect, **at most one reply per
   sender per 24 hours** (in-memory limiter). On WhatsApp each such reply is a billable message
   to a stranger; on both it stops the bot being a reflector.
4. **AI gate**: `AiAccessService.isAiAvailableForUser`, replying `command.ai_disabled` when off.
   Same behavior as Telegram.
5. **Dispatch**, mirroring `TelegramChannel`'s capture branches:
   - state in the registry → `quickAddFlow.handleInbound(...)`;
   - no state, text → `quickAddFlow.beginUnprompted(...)`;
   - no state, media → `fetchMedia()` then `quickAddFlow.beginUnpromptedFromMedia(...)`; a
     rejected fetch replies with the existing `quickadd.media.too_large` / `unsupported_type` /
     `download_failed` keys;
   - no state, selection (a tap on an expired card) → a short "that card has expired" reply.
6. Store or clear the returned state. `CaptureEntry.Routed` (any intent) → the capabilities
   reply described under Scope. New message key; do not reuse `command.unprompted.unclear`,
   which names slash commands.
7. Any exception: log (ids only), reply `command.handleUpdateFailed`, clear the state.

State lives in a new `MessagingCaptureRegistry`, keyed by `(ChannelType, senderId)`, with the same
15-minute TTL and `expired` metric as `QuickAddRegistry`. It is a third small class beside
`QuickAddRegistry` and `WebQuickAddRegistry`, on purpose: no caller is polymorphic over them
(different keys, and the web one also holds a board), so a common interface would have no user. `boardId` is always null: these
channels save to the default board, like Telegram.

**Why no persistent queue, when inbound email has one.** An email sender gets no feedback and
cannot tell that a message was lost, so email is queued and retried. A chat user sees that the
bot did not answer and sends the message again. Meta additionally redelivers for 7 days whatever
we did not acknowledge, which covers a deploy. So: acknowledge, process in memory, accept that a
crash mid-capture loses that one message.

### 2. Webhook endpoints and security

- Paths: `GET|POST /webhooks/whatsapp` (and `POST /webhooks/webex`, if Webex is ever built).
- A new stateless filter chain, `webhookFilterChain`, matching `/webhooks/**`, placed after the
  calendar-feed chain (the session chain moves to `@Order(6)`). CSRF off, no session ever
  created, `permitAll` for exactly those methods and paths, everything else denied. Model it on
  `calendarFeedFilterChain`.
- **Authentication is the signature**, checked in the controller, in constant time
  (`MessageDigest.isEqual`), over the raw body (`@RequestBody ByteArray`), before any parsing.
  A shared `WebhookSignatures.hmacHex(algorithm, secret, body)` helper is all the two providers
  have in common here; do not build a verifier abstraction beyond it (Slack adds a timestamp,
  Teams uses JWTs, Discord uses Ed25519).
- Bad or missing signature → `401`, nothing processed, one WARN without the body. Feature off or
  not configured → `404`.
- Good signature → `200` with an empty body, always, including for events we ignore (statuses,
  group messages, our own messages, unknown types) and for internal failures after the
  hand-off. A non-200 makes Meta redeliver for a week.
- Cap the body: reject above 3 MB (WhatsApp) and 64 KB (Webex) from `Content-Length`, before
  reading.
- The WhatsApp handshake compares `hub.verify_token` in constant time too. That token appears in
  the query string, so it reaches access logs: treat it as low-value and unrelated to any other
  secret.
- Never log a webhook body, a phone number, a Webex email, or message text. Provider message
  ids, our user ids and link ids are fine.
- `SecurityIntegrationTest`-style coverage: a `SESSION` cookie neither helps nor is created on
  `/webhooks/**`; an unsigned POST is 401.
- Hosted Nginx needs no change (the catch-all location proxies these). Note in the ops repo that
  `client_max_body_size` must allow 3 MB on that path.

### 3. Linking a chat account to a user

**A link is not an auth identity.** `auth_identities` rows are login methods: they count toward
the "never remove the last login method" guard in `AccountLinkService.unlink` and are listed as
connected accounts. A WhatsApp link there would let a user remove their only real login. So:

New changeset `022` (additive):

- `channel_link`: `id`, `user_id` (FK), `channel`, `external_id_hmac` (lookup key; unique with
  `channel`), `external_id_enc`, `display_enc` (the phone number or Webex email, for Settings),
  `linked_at`, `last_inbound_at`. Unique on `(user_id, channel)`: one account per channel.
- `channel_link_token`: `id`, `user_id`, `channel`, `token_hmac`, `expires_at`. At most one
  live token per `(user_id, channel)`.

Both external ids and the link code go through an HMAC under a KEK-derived key, the
`InboundMailTokenHasher` pattern, not plain SHA-256: phone numbers and short codes are
low-entropy. `*_enc` columns are encrypted under the user's DEK. The WhatsApp key is the
**BSUID**; the phone number is stored only for display and only when present.

`ChannelLinkService`:

- `startLink(userId, channel)`: mints a code (8 base32 characters, shown as `XXXX-XXXX`),
  15-minute TTL, single use, replacing any earlier one. Returns the code and, per channel, how to
  send it: for WhatsApp a `https://wa.me/<number>?text=<prefilled>` link (the SPA also renders it
  as a QR code for desktop users); for Webex the bot's address and the code to paste.
- `consume(channel, senderId, display, text)`: called by the router for unknown senders.
  Recognizes the code anywhere in a short message (case-insensitive, with or without the dash).
  Attempts are limited per sender (5 per hour). Outcomes: linked; code unknown or expired;
  **sender already linked to another account → refused**, same policy as Telegram (no merge);
  the user already has a different account linked on this channel → replaced.
- `unlink(userId, channel)`, `list(userId)`, `resolve(channel, senderId)`.
- Only **claimed** accounts may link (an unclaimed demo account is reaped; a link must not keep
  it alive, and is not a way back in).
- `AccountService.deleteUserData` clears both tables. Add the link (channel and date, no
  identifier) to the account export next to the Telegram fields.

API, on the session chain with CSRF, under `/api/v1/channel-links`: `GET` (which channels this
instance offers, and the user's links), `POST /{channel}/token`, `DELETE /{channel}`. Which
channels are on comes from this authenticated endpoint, not from the public config.

SPA: a "Messaging apps" block in Settings → Integrations, next to `InboundMailAddress.tsx`,
one row per offered channel: not connected (Connect → shows the link / QR / code and polls
`GET` until linked or expired) or connected (masked identifier, Disconnect). All four UI
languages; logical CSS properties.

### 4. Shared attachment policy

Extract from `TelegramMediaExtractor` into `channel/AttachmentIntake` (pure functions, no I/O):
the per-kind byte caps, "which `AttachmentKind` is this media type / filename", the audio
media-type → format label mapping, and the `TextAttachments` hand-off. `TelegramMediaExtractor`
then calls it; behavior must not change (its tests are the check). Each provider's fetcher
returns the same `Media | Rejected(reason)` shape, checks the declared size before downloading
and the actual size while reading (`readNBytes(limit + 1)`), and never writes bytes to disk.

Our caps (5 MB) stay below both platforms' limits. `InputModalitySupport` keeps gating images,
PDFs and voice inside `QuickAddFlow`, so nothing changes there.

### 5. WhatsApp adapter (`channel/whatsapp`)

- `WhatsAppProperties` (`tasker.whatsapp.*`): `enabled`, `phone-number-id`, `access-token`,
  `app-secret`, `verify-token`, `display-number`. Beans are conditional on enabled **and** the
  four secrets being set, with the `ConditionalOnTelegramBot` pattern, including the shared
  predicate used by `StartupSummaryLogger`.
- `WhatsAppClient` (Spring `RestClient`, Graph API version as one constant): send text, send
  reply buttons, send list, mark-read-with-typing, media metadata, media download.
- `WhatsAppWebhookController`: handshake; signature; flatten `entry[].changes[].value.messages[]`
  into `InboundEvent`s; ignore `statuses`, and messages whose `metadata.phone_number_id` is not
  ours. Types: `text`; `interactive` (`button_reply`, `list_reply`) → `Selection`; `image`,
  `audio`, `document` → `MediaRef` with the caption; everything else (video, sticker, location,
  contacts, reaction, `unsupported`) → an event that replies `quickadd.media.unsupported_type`.
- `WhatsAppConversationChannel`:
  - capabilities: buttons yes, autocompletions no, slash commands no;
  - addresses the reply with `to` when the event carried a phone number, else `recipient` with
    the BSUID;
  - `Text`: split at 4096 on paragraph boundaries; `preview_url` off, for the reason
    `TelegramConversationChannel.NO_LINK_PREVIEW` documents;
  - `Choice` with ≤ 3 options and every label ≤ 20 characters → reply buttons; otherwise a list
    message (≤ 10 rows, labels cut to 24 with an ellipsis); more than 10 options → a numbered
    text list of the labels, and the user answers in words (the flow already accepts free text
    in every state). A prompt over the body limit is sent first as plain text, then the buttons
    under a short localized line;
  - `whileWorking`: one mark-read-with-typing call for the inbound message, repeated every 20
    seconds while the block runs, capped like Telegram's.
  - A send that fails because the window has closed or the user blocked the number is logged
    and dropped. There is no reachability flag to maintain: we never push.
- `WhatsAppMessageFormatter`: `*bold*`, `_italic_`, `- ` lists. WhatsApp has no escape syntax,
  so `escape` returns the text unchanged; a stray `*` in a task title is cosmetic.
- Test that the fixed quick-add button labels (`quickadd.button.*`, `quickadd.plan.skip`, the
  day and time-of-day labels) are ≤ 20 characters in **every** message bundle, so they render as
  buttons rather than degrading silently.

### 6. Webex adapter (`channel/webex`) — deferred

Not scheduled; see "Why Webex is deferred". Kept as the design to start from.

- `WebexProperties` (`tasker.webex.*`): `enabled`, `bot-token`, `webhook-secret`. Same
  conditional pattern.
- `WebexClient`: `GET /people/me` (the bot's own id and address, cached at startup), get
  message, get attachment action, send message (Markdown, optional card), download file, list /
  create / update webhooks.
- `WebexWebhookRegistrar`: at startup, and then daily, make sure exactly two webhooks exist for
  `<base-url>/webhooks/webex` with our secret, `messages:created` and
  `attachmentActions:created`, and that both are `active`; create, update or re-activate as
  needed. Idempotent, and a failure only logs: the app must start without Webex.
- `WebexWebhookController`: signature (`X-Webex-Signature` if present and understood, else
  `X-Spark-Signature`); drop events from the bot itself and from non-direct rooms; return 200;
  the worker then fetches the message or action. Text → `Text`; `files` → `MediaRef` (first
  file only, as on Telegram; `HEAD` for type and size before `GET`; honor `423` + `Retry-After`
  a few times, then `download_failed`); action → `Selection(inputs.optionId)`.
- `WebexConversationChannel`: capabilities as WhatsApp. `Text` → Markdown message, split below
  the size limit. `Choice` → one Adaptive Card: the prompt as a `TextBlock`, one `Action.Submit`
  per option with `data: {optionId}`, and the prompt plus a numbered list of the labels as the
  `markdown` fallback. Formatter: the existing `MarkdownMessageFormatter`. No typing indicator.
- After a tap, delete or replace the card message if the API allows the bot to (verify), the
  equivalent of `TelegramChannel.collapseChoice`. If not, rely on the "that card has expired"
  reply.

### 7. Cross-cutting

- `ChannelType`: add `WHATSAPP("whatsapp")`.
- Metrics: `tasker.channel.inbound{channel,outcome}` with outcomes `captured`, `duplicate`,
  `unlinked`, `linked`, `ignored`, `ai_off`, `failed`; `tasker.channel.outbound{channel,outcome}`;
  `tasker.channel.link{channel,outcome}`. `QuickAddFlow`'s own counters stay as they are.
- Rate limits: the existing per-user `quickAdd` policy already bounds captures. New policies in
  `RateLimitProperties`: `channelLinkAttempt` (per sender) and `channelUnlinkedReply` (per
  sender).
- Logging budget per CLAUDE.md: one INFO per link / unlink, one DEBUG per inbound event.
- i18n: every new bot string in all message bundles (`@APP_NAME@` / `@APP_URL@`, never the
  literal name), `en-GB` overrides where spelling differs, gender-neutral Hebrew and Arabic. The
  reply to an unknown sender is English, as Telegram's `command.start` is: there is no account
  to carry a language.
- Prompt safety: unchanged. Text and files reach the model through the same `QuickAddFlow`
  paths as Telegram's.
- Docs to update with the code: `CLAUDE.md` (seven filter chains; a short "Messaging channels"
  section with the invariants from sections 2 and 3), `docs/CONFIGURATION.md`,
  `docs/SELF-HOSTING.md` and `deploy/.env.example` (both channels optional and off by default),
  `docs/SPEC.md` if it lists channels, and the Privacy / Terms content pages (Meta becomes a
  processor of message content). Turn this file from a plan into the as-built note, and
  add a pointer at the top of `docs/WHATSAPP-CHANNEL.md`.

## Work packages

Each is one PR, green on both CI jobs, and shippable on its own (the features stay dark until
configured).

1. **Foundation.** `ChannelType` values; changeset `022`, entities, `ChannelLinkService`, its
   API and tests; `MessagingCaptureService`, registry, dedup, per-conversation serialization;
   `AttachmentIntake` extraction; `webhookFilterChain`; metrics; deletion and export. Tested with
   a fake provider (an in-memory `ConversationChannel` and hand-built `InboundEvent`s): link by
   code, refuse a conflicting link, capture, card → save, duplicate event, burst of two messages,
   expired card, AI off, unknown sender capped at one reply.
2. **Settings UI.** The "Messaging apps" block, four locales, component tests. Shows nothing
   while no channel is enabled.
3. **WhatsApp adapter.** Needs the owner prerequisites below, which have lead time; start them
   during package 1.
4. **Docs and policy pages.**

Deferred, not scheduled: the **Webex adapter** (section 6). If it is ever picked up, resolve the
"(verify)" items against the live API first. Until then, add no `WEBEX` channel type, no
`tasker.webex.*` configuration and no Webex row in Settings.

Owner prerequisites (outside the repo):

- WhatsApp: a Meta business portfolio and app, a WhatsApp Business Account, a dedicated number
  not in use on the consumer app, a system-user access token, the app secret, the webhook
  subscribed to the `messages` field. Re-read the AI-providers terms first.
- Ops repo: the new environment variables; body-size note for Nginx.

## Verification

- Unit: signature helpers against known vectors; payload parsing against recorded fixtures
  (text, button reply, list reply, image with caption, voice note, status-only, BSUID-only
  sender, batched POST); the channel
  adapters' splitting and button / list / fallback selection; button-label lengths per bundle.
- Slice: each webhook controller with `@Import(SecurityConfiguration::class)`: no session
  cookie set, 401 unsigned, 200 signed, handshake echo, oversized body rejected.
- End to end, per channel, on a tunnelled dev instance, then on production: link from Settings;
  send a line of text → card → Save → task on the default board; Adjust; a photo; a voice note;
  an unsupported file; a message from an unlinked account (one reply, then silence); Disconnect,
  then a message (treated as unlinked).

## Fit for later channels

| Channel | Transport and verification | Buttons | Fits as |
|---|---|---|---|
| Slack | Events API webhook; HMAC-SHA256 over timestamp + body; URL-verification challenge; 3-second ack, retries | Block Kit, taps on a separate interactivity URL | Controller + client + channel. Same shape as Webex. |
| Microsoft Teams | Bot Framework webhook; JWT bearer validation, not HMAC | Adaptive Cards | Same, with a JWT verifier. Card rendering shared with Webex. |
| Google Chat | HTTP endpoint; Google-signed JWT | Cards | Same. |
| Discord | Interactions webhook (Ed25519) covers commands and buttons only; plain DMs need a gateway websocket | Components | Needs a long-lived connection, like Telegram's poller; the router still applies. |
| SMS (Twilio or similar) | Form-encoded webhook; HMAC-SHA1 over URL + params | None | Numbered-list replies only; per-message cost both ways. |
| Signal, iMessage | No official bot API | – | Not a fit. |

What stays per-provider is the controller, the client and the `ConversationChannel`. What is
shared, and should not be re-implemented, is the router, the registry, linking, attachment
policy, metrics and the filter chain.

## Corrections to earlier notes

- `docs/WHATSAPP-CHANNEL.md` proposes storing the link as an `auth_identities` row keyed by the
  WhatsApp id, with the phone number as lookup handle. Superseded by section 3: a separate
  `channel_link` table, keyed by BSUID.
- The same note says in-window replies are free. They are free up to 1,000 per month, then
  billed (since October 2026).
- Its "reactive-only" recommendation, the click-to-chat linking idea and the Cloud API choice
  all stand.

## Sources

- Meta: [webhooks overview](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/overview),
  [create a webhook endpoint](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/create-webhook-endpoint),
  [messages webhook reference](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages),
  [business-scoped user IDs](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-scoped-user-ids),
  [media](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-phone-numbers/media),
  [reply buttons](https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/interactive-reply-buttons-messages),
  [list messages](https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/interactive-list-messages),
  [typing indicators](https://developers.facebook.com/documentation/business-messaging/whatsapp/typing-indicators),
  [pricing](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing)
- Cisco: [using a webhook secret](https://developer.webex.com/blog/using-a-webhook-secret),
  [buttons and cards](https://developer.webex.com/messaging/docs/buttons-and-cards),
  [webhooks guide](https://developer.webex.com/docs/webhooks),
  [bots](https://developer.webex.com/docs/bots)
