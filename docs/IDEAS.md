## Ideas File
This file is for capturing random ideas that don't fit into the current spec but might be worth exploring later. 
The scope of the individual idea is varying - could be small UI improvements, or large features that change the entire app.

## Small Improvements and Concerns
- Revise-session summary maintenance is prompt-only and fragile. On revise, `revisePlan` overwrites
  `session.summary` wholesale with whatever the model returns, so a thin/regenerated summary silently
  drops the prior durable context. We've leaned on prompt emphasis ("merge, don't replace") to hold
  the line, but the model still regresses sometimes. If it keeps slipping, move to a sturdier
  mechanism instead of trusting the model to rewrite the whole note each time — e.g. keep the original
  planning summary immutable and store revise deltas separately, or only accept the new summary when
  it materially grew rather than shrank.
- Client side error messages - more friendly? error reference? email support?
- `tasker-frontend/index.html` still reflects the pre-redesign welcome page: the `#prerendered-landing`
  crawler fallback, plus the meta/OG/Twitter/JSON-LD copy, describe the old Telegram-only flow and the
  previous headline/pitch. Update them to match the redesigned landing (new headline "Tasks you keep /
  actually doing.", multi-provider sign-in: Telegram + Google + Email). Verbiage needs a human pass
  before shipping.
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones. **Now visible in the product**: with `he` launched, the display face falls through to a generic serif, and italic-styled text gets a *synthetic oblique* — Hebrew has no true italic, so it reads as a rendering fault rather than emphasis. Same call as `docs/I18N.md` open question 1. Cheapest correct floor is `font-synthesis: none` under `[dir="rtl"]` plus a Hebrew display stack; choosing the face is the product decision.
- Persisted calendar invite SEQUENCE counter. Plan-revise updates re-send same-time slot edits (label/title/notes) with a fixed `SEQUENCE:1`. A second same-slot edit in a later session sends `SEQUENCE:1` again, which strict calendar clients may not re-apply. Persisting a per-slot revision counter (incremented on each update) would make repeated updates robust. Low priority: time moves go through cancel + fresh invite, which is unaffected.
- Currently, a single task is tied to a single time-block; would we like to change that, so that a single task might have multiple (or zero) time blocks attached?
- Add a search functionality.
- Notifications: toggle whether calendar invite emails include a notification, or not (in case users prefer other means of notifications and just want the calendar sync for blocking time).
- Notifications: consider web push notifications (https://web.dev/articles/push-notifications-overview) - they work even if the users are not active in the site.

## Auth & accounts
- Add Google OAuth as a login provider — drops in as another `loginOrRegister('google', sub, …)`
  caller + identity rows, no schema change needed. This is blocked till we have a dedicated Google 
  account (rather not risk my personal account)
- Retire the legacy `users.telegram_id` / `users.email_hash` columns once nothing reads them as a
  lookup key (identity resolution already goes through `auth_identities`).
- Account-linking UX: linking an identity already owned by a different account is currently just
  refused (409). Decide if/how to offer a real merge flow, including how to re-prove ownership of
  the other account before merging.

## Landing page & SEO
- Add a real screenshot of the board and wire it as `og:image` / `twitter:image` (biggest legibility
  win for both humans and link-preview bots); also covers the missing Apple touch icon / 512×512 PNG
  for share sheets.
- Footer: GitHub link (if going with AGPL), contact/about line (ToS/Privacy are already linked).
- Optional: an FAQ (data handling, why Google Calendar, account deletion), a `/about` page.
- Accessibility pass on the login page: visible focus rings, WCAG AA contrast check on the body text,
  `<main>`/`<header>`/`<footer>` landmarks, alt text once the screenshot lands.

## Board sharing follow-ups
- No per-board planner fairness caps yet — a busy shared board could in principle crowd out a
  quiet one in the candidate pool (see `docs/BOARD-MODEL.md`).
- No viewer/commenter role tier or per-task permissions beyond the assignee primitive — only add
  if real usage demands it.

## External API follow-ups
- **Hash the remaining capability tokens.** `email_login_token.token`, `board_invitation.token`
  and `users.email_verification_token` are all stored **unhashed**, and are generated from
  `UUID.randomUUID()` rather than `SecureRandom` (`EmailLoginService.kt`,
  `EmailVerificationService.kt`). Anyone with a DB dump or read replica can mint a login as any
  user with a pending magic link. `ApiTokenService` now has the right pattern to copy
  (SecureRandom + store only the SHA-256 digest); the migration needs a cutover plan since
  existing rows can't be re-hashed without invalidating them — probably just expire them all,
  given the 30-minute TTL.
- Remote MCP server over the external API. The REST surface is the substrate; MCP adds transport
  and tool schemas. Only worth it if a client appears that can't read `/external-api/SKILL.md`.
- Free-text search (`?q=`) decrypts every one of the user's tasks per call, because titles and
  descriptions are envelope-encrypted and can't be filtered in SQL. Fine at current scale; if a
  user ever holds thousands of tasks this needs a searchable index (blind index on tokenised
  terms, or a per-user encrypted search structure).
- Token scope is coarse — read vs. write, all boards. Per-board or per-operation scoping only if
  real usage demands it.
- Token expiry is supported by the schema (`expires_at`) but not yet exposed in the UI; every
  token minted today is non-expiring until revoked.
- `InMemoryRateLimiter` and the `last_used_at` write throttle both assume a single instance.
  Both need Redis if the app is ever replicated.

## Production hardening
- Support/abuse contact address, referenced from ToS + Privacy.
- Smoke test asserting `dev-login` actually 404s/401s in prod, rather than trusting the
  `@Profile("dev")` annotation alone.
- A cap on simultaneous unclaimed accounts (separate from the inactivity-based cleanup sweep, which
  already exists).
- Error tracking (Sentry / GlitchTip) — distinct from log collection: dedup, stacktraces, release
  tagging.
- A periodic backup *restore* drill, not just backups.
- Graceful shutdown + readiness probe wired into the deploy pipeline so rollouts don't drop in-flight
  requests.

## UI - Tasks
- Work on tagline and satellite notes in the welcome page with better texts. See if we need to move a few things around 
- Better - more satisfying - "mark as done"
- Drawer improvements (buttons are too dense, for example)
- Settings dialog - each tab has a different height; switching tabs move the modal. Also, consider
  moving some fields from the "General" area to somewhere more relevant. **Raised priority**: the
  Integrations tab (API tokens) makes four tabs, which overflows the tab row on most mobile widths.
  Shipped as-is deliberately; see the "Categories and tags" item below for the intended fix.
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen
- Filter chips can still wrap on very small screens even after the "Week" shortening. If it keeps bugging us, consider a segmented control or horizontally-scrollable chip row on mobile.
- Board management: custom board color pin marker (the member count pin)?
- Center pill bar on mobile; consider dropping the "done" pill.
- Categories and tags should go in the "Board Settings" menu — they are board-scoped, while the rest
  of the settings dialog is user-scoped. **Now the leading candidate for the mobile tab overflow
  above**: moving them out drops the settings dialog back to three tabs (General, Assistant,
  Integrations) without dropping any functionality.
- Setting dialog - notifications tab?

## Assistant - Mid-week response
- When texting the assistant out of the blue, respond with the correct context. 

## Following up
- The assistant could follow up on tasks that were scheduled but not marked done after their scheduled time. 
- It could ask if we got them done, if we want to reschedule, or if we want to move them back to the backlog.

## Proactive Task Helper
- We'll recognize tasks that are repeatedly rescheduled or not marked done, and proactively suggest help.
- Possible help ideas include breaking them down to multiple tasks, finding time for them, or even just reminding us about them.

## Web UI i18n follow-ups
Deferred from the `docs/I18N.md` design (see there for full rationale). Not blocking any phase:
- Login-page (anonymous) language switcher — browser detection covers the first iteration; a
  `localStorage` override slotted above browser detection is a cheap add later.
- Localized `document.title` / meta tags (the SPA sets `lang`/`dir` at runtime; the static
  `index.html` SEO surface deliberately stays English).
- A one-line localized notice on the legal pages ("This document is available in English only");
  the ToS/privacy text itself stays English-only.
- Broader server-error `code` coverage — codes are added opportunistically per flow as each is
  translated, not as a big-bang backend sweep.
- Re-adding any of the dormant language bundles (`de/es/fr/it/ja/ko/nl/pt/zh`) if demand appears;
  they're frozen, not deleted.
- Hebrew/Arabic display typography that preserves the paper/post-it aesthetic — a product/design
  decision (this is the same concern as the "Fonts look bad in Hebrew" note above).
- Retrofit the channel bundles (`messages_he.properties`, `messages_ar.properties`) to the
  gender-neutral phrasing the web UI catalog uses. They currently rely on slash forms
  (`תרצה/תרצי`, `סמן/י`), which `CLAUDE.md` rules out; the web catalog shows the alternative —
  verbal nouns for actions, impersonal phrasing for instructions. Mechanical but not trivial:
  Telegram copy is conversational, so some lines need rewriting rather than substitution.

## Ideas that require more consideration
- Open source the application under AGPL. A full git-history secret scan ahead of this switch
  came back clean — see `docs/GIT-HISTORY-SECRET-SCAN.md` for methodology and findings.
- Unlock more mascots for users over use time or patterns.
- Additional themes.
- Add "description" to a tag (the database field is already there - but would it be useful?).
- Birthday calendar, or general reminders.
- Support non-latin calendars.

## Large projects
- WhatsApp as a communication channel support.
- **Template task boards** — pre-populated boards for life events ("relocating to Germany", "long
  trip"), possibly with a public SEO-facing gallery. **Deferred**: the value hinges on
  intent-driven acquisition (someone searching for that checklist), which itself requires the
  costly SEO/content bet up front — and without that channel there's no strong starter-template
  story for existing sign-ups. Extended discussion and design sketch: `docs/TEMPLATE-BOARDS.md`.
  The cheap adjacent win, a "duplicate board" action, has since shipped (any member can copy a
  board's categories/tags/tasks into a fresh board they solely own — `BoardService.duplicateBoard`).
- **Web UI i18n** — design and phased rollout live in `docs/I18N.md`. Phase 0 (trim the supported
  languages to en/he/ru/ar), Phase 1 (i18next + locale-resolution infrastructure, `Intl` formatting
  helper, full string extraction, key-parity test) and Phase 2a (the RTL direction pass: CSS logical
  properties, direction-aware components, mirrorable arrows, `?uiLang=he` dev preview, guard tests)
  have landed, as has Phase 2b (the Hebrew catalog and the `he` launch, RTL-QA'd) and the Russian
  half of Phase 3 (catalog-only, `ru` launched — no RTL/font work needed). The Hebrew display-type
  decision (see "Fonts look bad in Hebrew" above) is deliberately left open — not blocking. Arabic
  is the one phase left.
  `docs/I18N-INVENTORY.md` tracks per-component extraction status.
- Multi-modal support: the assistant can process images and voice messages. **Partly shipped** —
  a Telegram quick-add can now be described with a photo or a voice note instead of typed text
  (design and decisions: `docs/MULTIMODAL-CAPTURE.md`). It is off until `TASKER_AI_MULTIMODAL_MODEL` names a model that
  advertises the matching input modality; until then the bot declines media with a "describe it"
  reply. Still open: what an out-of-band attachment (one sent with no quick-add in progress) should
  do, PDFs, albums (`media_group_id` grouping), media inside the planning conversation, and media on
  the web UI.
