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
- Fonts look bad in Hebrew and Arabic (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones. **Now visible in the product**: with `he` and `ar` both launched, the display face falls through to a generic serif for either script, and italic-styled text gets a *synthetic oblique* — neither Hebrew nor Arabic has a true italic, so it reads as a rendering fault rather than emphasis. Same call as `archive/I18N.md` open question 1. Cheapest correct floor is `font-synthesis: none` under `[dir="rtl"]` plus per-script display stacks; choosing the faces is the product decision.
- Persisted calendar invite SEQUENCE counter. Plan-revise updates re-send same-time slot edits (label/title/notes) with a fixed `SEQUENCE:1`. A second same-slot edit in a later session sends `SEQUENCE:1` again, which strict calendar clients may not re-apply. Persisting a per-slot revision counter (incremented on each update) would make repeated updates robust. Low priority: time moves go through cancel + fresh invite, which is unaffected.
- Currently, a single task is tied to a single time-block; would we like to change that, so that a single task might have multiple (or zero) time blocks attached?
- Add a search functionality.
- Notifications: toggle whether calendar invite emails include a notification, or not (in case users prefer other means of notifications and just want the calendar sync for blocking time).
- Notifications: consider web push notifications (https://web.dev/articles/push-notifications-overview) - they work even if the users are not active in the site.
- Why do we have both English (UK) and English (US) if we only support English US? Either support it properly or drop it.
- "App was updated" notice on mobile - looks bad. Use less text and fix CSS.

## Auth & accounts
- Add Google OAuth as a login provider — drops in as another `loginOrRegister('google', sub, …)`
  caller + identity rows, no schema change needed. This is blocked till we have a dedicated Google 
  account (rather not risk my personal account)
- Account-linking UX: linking an identity already owned by a different account is currently just
  refused (409). Decide if/how to offer a real merge flow, including how to re-prove ownership of
  the other account before merging.

## Landing page & SEO
- Footer: GitHub link, if we go with AGPL. (The footer itself now ships — About/FAQ/Terms/Privacy.)
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
  user ever holds thousands of tasks this needs a searchable index (blind index on tokenized
  terms, or a per-user encrypted search structure).
- Token expiry is supported by the schema (`expires_at`) but not yet exposed in the UI; every
  token minted today is non-expiring until revoked.
- `InMemoryRateLimiter` and the `last_used_at` write throttle both assume a single instance.
  Both need Redis if the app is ever replicated.
- **Expiry for external API tokens** — `api_token` rows never expire, which now makes them the
  longest-lived credential in the system.

## Production hardening
- A periodic backup *restore* drill, not just backups.
- Graceful shutdown + readiness probe wired into the deploy pipeline so rollouts don't drop in-flight
  requests.

## UI - Tasks
- Work on tagline and satellite notes in the welcome page with better texts. See if we need to move a few things around 
- Better - more satisfying - "mark as done"
- Drawer improvements (buttons are too dense, for example)
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen
- Filter chips can still wrap on very small screens even after the "Week" shortening. If it keeps bugging us, consider a segmented control or horizontally-scrollable chip row on mobile.
- Board management: custom board color pin marker (the member count pin)?
- Center pill bar on mobile; consider dropping the "done" pill.
- Setting dialog - better way to organize it?

## Following up
- The assistant could follow up on tasks that were scheduled but not marked done after their scheduled time. 
- It could ask if we got them done, if we want to reschedule, or if we want to move them back to the backlog.

## Proactive Task Helper
- We'll recognize tasks that are repeatedly rescheduled or not marked done, and proactively suggest help.
- Possible help ideas include breaking them down to multiple tasks, finding time for them, or even just reminding us about them.

## Web UI i18n follow-ups
Deferred from the `archive/I18N.md` design (see there for full rationale). Not blocking any phase:
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

## Ideas that require more consideration
- Open source the application under AGPL. A full git-history secret scan ahead of this switch
  came back clean — see `docs/GIT-HISTORY-SECRET-SCAN.md` for methodology and findings.
- Unlock more mascots for users over use time or patterns.
- Additional themes.
- Add "description" to a tag (the database field is already there - but would it be useful?).
- Birthday calendar, or general reminders (probably not: can be standalone focused product)
- Support non-latin calendars (probably not: it involves a lot of effort for almost no gain).
- Add multi-modal support to the planning conversation; support more files, like PDFs.

- **Per-session revoke in "active sessions"** — today Settings only offers "sign out everywhere
  else". Per-session revocation would mean stamping an opaque random ref per session (never the
  real session id, which must not be handed to a browser that might be the attacker's) and mapping
  ref → session on delete. Not worth the surface for the current user count.
- **New-sign-in notification** (Telegram / auth email: "new sign-in from Chrome on macOS"). This is
  what would turn the active-sessions list from forensics into actual detection — a user only
  revokes a session if something tells them to look. The highest-value follow-up now that the
  absolute session lifetime is a year rather than 90 days. Does it make sense when the login itself
  is through that same email? Might make sense for users with more than one channel.
- Scheduled tasks that appear in a fixed interval, possibly supporting more sophisticated schedules
  like "last day of the month", for recurring tasks (examples: pay rent, dentist, clean AC filters).
  Useful on one hand, but correct UI/UX is tricky, and this could be steering off the main focus
  towards a classic calendar territory.
- Incoming email address, as an additional way to quick-add tasks.

## Large projects
- WhatsApp as a communication channel support.
- **Template task boards** — pre-populated boards for life events ("relocating to Germany", "long
  trip"), possibly with a public SEO-facing gallery. **Deferred**: the value hinges on
  intent-driven acquisition (someone searching for that checklist), which itself requires the
  costly SEO/content bet up front — and without that channel there's no strong starter-template
  story for existing sign-ups. Extended discussion and design sketch: `docs/TEMPLATE-BOARDS.md`.
  The cheap adjacent win, a "duplicate board" action, has since shipped (any member can copy a
  board's categories/tags/tasks into a fresh board they solely own — `BoardService.duplicateBoard`). 
