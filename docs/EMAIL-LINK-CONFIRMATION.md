# Email link confirmation — surviving link-protection scanners

Status: **Implementation plan — not started.**

## Problem

Outlook (Safe Links / Advanced Threat Protection) and similar corporate email security products
**fetch links in incoming mail before or when the user clicks them**. Our token-bearing email links
perform their side effect on a bare `GET`, so the scanner's prefetch consumes the single-use token
and the human who then clicks lands on "invalid or expired link". Observed in testing with
Outlook-based accounts; this is an industry-wide, well-known failure mode of magic links.

Affected links (everything we email that carries a single-use token):

1. **Login magic link** — `GET /api/auth/email/callback?token=…`
   (`EmailAuthController.callback`): consumes the token, creates the session, 302-redirects into
   the app. A scanner hit burns the token *and* may even establish a session in the scanner's
   sandbox.
2. **Settings email verification** — `GET /api/v1/settings/email/verify?token=…`
   (`UserSettingsController.verifyEmail`): consumes the token, marks the email verified,
   302-redirects. A scanner hit verifies the email "by robot" — the flow appears to work, but the
   single-use guarantee is gone and a *failed* prefetch race still strands the user.
3. **Board invitations** (Phase 2, planned — `docs/BOARD-SHARING-PHASE2.md`): the accept step is
   already designed as GET-preview + authenticated `POST /accept`, so it is scanner-safe by
   construction. But its *login leg* for unauthenticated invitees rides the magic link (1), so it
   inherits this fix.

## Decision: confirm-on-POST interstitial

The link in the email stops being the action. It opens a **side-effect-free SPA page** that shows a
single explicit button ("Sign in" / "Verify email"); clicking it issues the **POST** that consumes
the token. Scanners follow GETs and render pages, but they do not click buttons that submit
state-changing forms — this is the same pattern the invitation accept screen already uses, now
applied uniformly: **a `GET` with a token in it must never have a side effect.**

### Alternatives considered (rejected)

- **Auto-submitting the POST from JS on page load** — defeats the point: SafeLinks detonates pages
  in a sandbox that executes JS. The human click is the discriminator; keep it.
- **Detecting scanners** (User-Agent lists, `HEAD`-vs-`GET`, timing heuristics) — an arms race with
  no contract; silently breaks when vendors change behavior.
- **Tolerating N uses / a reuse grace window** — weakens single-use semantics for every token to
  accommodate a subset of mail providers; the scanner may also *follow* the redirect and hold a
  session.
- **One-time code (OTP) entry instead of links** — actually the strongest option (also fixes the
  cross-device case: read the mail on your phone, type the code on your laptop), but it's a larger
  UX and template change. Deferred as a possible future evolution; the interstitial is compatible
  with adding OTP later (same token table, different presentation).

## Design

### Login magic link

- **Email URL** (built in `EmailLoginService.requestLogin`, the `login_url` template variable)
  changes from the backend callback to a SPA route:
  `${baseUrl}/email-login?token=…` (registered in `SpaForwardController`).
- **New page** `EmailLoginConfirmPage` (route `/email-login`):
  - On load, calls a new **side-effect-free** validity check —
    `GET /api/auth/email/precheck?token=…` → `{ valid: boolean }` — purely so an expired/used link
    shows its error before the user clicks. No token state changes; scanner hits are harmless.
    (Uniform response; the token itself is the only secret, so "valid: false" leaks nothing.)
  - Button → `POST /api/auth/email/callback` `{token}` (same path, new verb; the GET mapping is
    repurposed — see transition below). The handler runs today's `completeLogin` logic: consume
    (single-use, consume-before-resolve unchanged), `SessionAuthenticator.authenticate`, return the
    outcome as JSON (`success` / `unverified` / `invalid`) instead of a 302. The page navigates
    client-side on success and renders the error states inline — retiring the
    `/?emailLogin=invalid|unverified` redirect-param mechanism on `LoginPage` (the notice moves to
    where the user actually is).
- **`next` redirect threading** (needed by Phase 2 invitations): `POST /api/auth/email` accepts an
  optional `next`; it rides the login URL (`/email-login?token=…&next=…`) and the confirm page
  navigates there on success. Validate it with the existing `localRedirect` guard logic
  (relative path only, no `//`, no `\`) — promote that helper to a shared util.

### Settings email verification

Same shape, smaller stakes:

- **Email URL** (built in `EmailVerificationService.requestVerification`, `verify_url`) →
  `${baseUrl}/email-verify?token=…` (SPA route in `SpaForwardController`).
- **New page** `EmailVerifyConfirmPage`: precheck (optional — can reuse a shared confirm-page
  component), button → `POST /api/v1/settings/email/verify` `{token}` running
  `confirmVerification`, JSON outcome, inline success/error. The `/?emailVerified=true` redirect
  param retires with it.
- Note the verify link may be opened in a browser with **no session** (different device); like
  today's GET, the POST stays unauthenticated — the token is the credential.

### Board invitations (alignment only — no change to the Phase 2 plan)

- The invite email links to `/invite?token=…`, whose GET preview is already side-effect-free and
  whose accept is already an authenticated POST. ✔
- The Phase-2 pitfall about threading `next` through the magic-link callback is *solved by this
  design* (the confirm page carries `next`); `BOARD-SHARING-PHASE2.md` is updated to reference this
  doc instead of re-solving it.
- Ordering: this change should land **before** Phase 2's PR 2 (invitations), so the invitee login
  leg is scanner-safe from day one and the `next` plumbing exists to build on.

### Security configuration

- `SecurityConfiguration`: the two POSTs join the unauthenticated set
  (`/api/auth/email/callback` is already `permitAll`; add `/api/v1/settings/email/verify` POST —
  the path is already `permitAll` for the legacy GET) and the **CSRF ignore list**
  (`/api/auth/email/callback`, `/api/v1/settings/email/verify`). Justification matches the existing
  login exemptions: the requests carry no session-derived authority — the token in the body is the
  entire credential, so CSRF adds nothing. The precheck endpoint is a `permitAll` GET, no CSRF
  concern.
- Rate limiting: the consume POSTs and the precheck get the same posture as today's callback
  (token space is 64 random hex chars — brute force is not realistic, but keep the endpoints behind
  the standard request logging/metrics).

### Transition for in-flight links

Old emails contain the old GET URLs. Both legacy GET handlers stop consuming and instead
**302-redirect to the corresponding confirm page**, carrying the token
(`/email-login?token=…`, `/email-verify?token=…`):

- Zero-downtime: a link sent before the deploy works after it (and becomes scanner-safe in the
  act).
- The login redirect shim is two lines and can simply stay; token TTLs (30 min login / 24 h verify)
  mean it's only *needed* for a day, but there's no cost to keeping it and it guards against any
  cached/bookmarked URL.

## Frontend summary

- New routes `/email-login`, `/email-verify` in the router + `SpaForwardController`.
- One shared `TokenConfirmPage` component (title, explanatory line, single button, error states),
  specialized for login and verify; copy makes the extra click make sense ("Confirm it's you…").
  Localization note: these pages are part of the **web UI**, which is English-only per `CLAUDE.md`
  — no message-bundle work; the *emails* keep their existing localized templates with only the URL
  variable changing.
- `api.ts`: `precheckEmailLogin(token)`, `completeEmailLogin(token)`, `confirmEmailVerification(token)`;
  the completion calls are plain POSTs (no XSRF header needed — exempt — but `api.ts` sends it
  anyway when the cookie exists; harmless).
- `LoginPage`: drop the `?emailLogin=…` notice handling once the shim no longer produces it.

## Tests

- Controller slice: POST callback happy path / invalid / unverified outcomes as JSON; legacy GET
  redirects with the token and does **not** consume (the regression test that encodes this whole
  design); precheck never mutates; verify POST without a session succeeds.
- `SecurityIntegrationTest`: CSRF exemption for the two POSTs, `next` validation (absolute URL /
  `//` rejected → falls back to `/`).
- Service tests unchanged — `EmailLoginService.completeLogin` / `confirmVerification` keep their
  contracts; only the HTTP shape moved.
- Manual: re-test with the Outlook account that exposed the issue (the only real oracle for
  scanner behavior).

## Scope

One PR (backend + frontend + the `BOARD-SHARING-PHASE2.md` cross-reference edit). No schema
changes, no new tables — the token entities and services are untouched.
