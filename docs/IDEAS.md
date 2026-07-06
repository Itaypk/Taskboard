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
- Fonts look bad in Hebrew (especially the header - serif - ones). Either choose one that support multilanguage, or use language-specific ones.
- Persisted calendar invite SEQUENCE counter. Plan-revise updates re-send same-time slot edits (label/title/notes) with a fixed `SEQUENCE:1`. A second same-slot edit in a later session sends `SEQUENCE:1` again, which strict calendar clients may not re-apply. Persisting a per-slot revision counter (incremented on each update) would make repeated updates robust. Low priority: time moves go through cancel + fresh invite, which is unaffected.
- Currently, a single task is tied to a single time-block; would we like to change that, so that a single task might have multiple (or zero) time blocks attached?
- Add a search functionality.
- Let the assistant *propose* additions to the user context block at the end of a planning session
  (e.g. "want me to remember you prefer no work Tuesday evenings?"), with the user accepting/rejecting
  in the UI — keeps the user as sole editor while lowering the friction of growing the block over time.
  See the "Soft extension" note in `docs/MEMORY-MODEL.md`.

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
- Settings dialog - each tab has a different height; switching tabs move the modal. Also, consider moving some fields from the "General" area to somewhere more relevant.
- Task list Markdown (subtasks) checkboxes - makes it possible to check directly from the main screen
- Filter chips can still wrap on very small screens even after the "Week" shortening. If it keeps bugging us, consider a segmented control or horizontally-scrollable chip row on mobile.
- Board management: custom board color pin marker (the member count pin)?
- Center pill bar on mobile; consider dropping the "done" pill.

## Assistant - Mid-week response
- When texting the assistant out of the blue, respond with the correct context. 

## Following up
- The assistant could follow up on tasks that were scheduled but not marked done after their scheduled time. 
- It could ask if we got them done, if we want to reschedule, or if we want to move them back to the backlog.

## Proactive Task Helper
- We'll recognize tasks that are repeatedly rescheduled or not marked done, and proactively suggest help.
- Possible help ideas include breaking them down to multiple tasks, finding time for them, or even just reminding us about them.

## Ideas that require more consideration
- Open source the application under AGPL.
- Unlock more mascots for users over use time or patterns.
- Additional themes.
- Add "description" to a tag (the database field is already there - but would it be useful?).

## Large projects
- WhatsApp as a communication channel support.
- Complete i18n support, including the web UI, welcome page, etc. Consider trimming the list of supported languages.
- Multi-modal support: the assistant can process images and voice messages. 
