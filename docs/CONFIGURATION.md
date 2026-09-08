# Configuration reference

Environment variables read by the backend and baked into the frontend bundle. The rationale that
matters while writing code (the KEK warning, the multimodal gate, the tier-cap fallback, the
two-sender email split, the blocklist's loud rejection) stays in `CLAUDE.md` — this file is the
name/default/provenance reference.

## Telegram (`TelegramAuthProperties`)

- `TASKER_TELEGRAM_CLIENT_ID` — OIDC client id (bot id) for login; also the expected `aud` of the
  id_token. From BotFather → Bot Settings → Web Login.
- `TASKER_TELEGRAM_CLIENT_SECRET` — OIDC client secret (HTTP Basic credential for the token
  exchange). From the same BotFather screen.
- `TASKER_TELEGRAM_BOT_TOKEN` — bot messaging token (planning conversation); not used by login.
- `TASKER_TELEGRAM_BOT_USERNAME` — cosmetic / future use.

## AI

- `TASKER_AI_MULTIMODAL_MODEL` — model slug used for Telegram quick-add captures that carry an
  attachment (photos, voice notes); defaults to the task-assistant model. See
  `docs/MULTIMODAL-CAPTURE.md`.
- `TASKER_AI_TIER_CAP_MAX_GRANTED_USERS` — hard cap (default `50`) on claimed accounts holding the
  full `standard`/`unlimited` grant at once; a budget safety valve while the app runs on a prepaid,
  budget-limited OpenRouter key. Enforced in `UserSettingsService.initializeForNewUser` and
  `upgradeToStandardOnClaim`. Read via `AiProperties.tierCap` / `AiTierCapProperties`.

## Database (required under the `prod` profile)

- `TASKER_DB_URL` — JDBC URL, e.g. `jdbc:postgresql://host:5432/taskboard`.
- `TASKER_DB_USERNAME` / `TASKER_DB_PASSWORD` — Postgres credentials.

## At-rest encryption (required in prod)

- `TASKER_DATA_KEK` — base64-encoded 32-byte key wrapping the per-user DEKs that encrypt task
  titles, descriptions, LLM messages, user settings, etc. Generate with `openssl rand -base64 32`;
  store the prod value offline (e.g. a password manager). Dev/test fall back to a checked-in
  placeholder key — never reuse that for prod. **Losing this key permanently loses all encrypted
  data** (see `CLAUDE.md`).

## Metrics (`PrometheusAuthProperties`) — required in prod, dev defaults apply otherwise

- `TASKER_PROMETHEUS_USERNAME` — Basic Auth username for `/actuator/prometheus` (default:
  `prometheus`).
- `TASKER_PROMETHEUS_PASSWORD` — Basic Auth password (default: `prometheus-dev`).

## Email

- `TASKER_EMAIL_ENABLED` — toggle the email integration (default: `false`). When false, both senders
  log instead of sending, and the magic link is printed to the log.
- `TASKER_EMAIL_BLOCKED_DOMAINS` — comma-separated **additions** to the application-wide blocklist
  (`EmailDomainBlocklistService`). The bulk of the list is a vendored ~75k-domain snapshot of
  [disposable/disposable-email-domains](https://github.com/disposable/disposable-email-domains)
  (MIT), shipped gzipped at `src/main/resources/email/disposable-domains.txt.gz` and refreshed by
  `tools/refresh-disposable-domains.sh` — vendored rather than fetched at runtime so boot needs no
  network and tests run offline. Bundled entries match exactly; a config entry matches its domain
  exactly, or prefix it with `*.` to match subdomains only, not the domain itself — list both forms
  to block a domain and all its subdomains.
- Auth sender (login / register / verification magic links): `TASKER_EMAIL_AUTH_FROM`,
  `TASKER_EMAIL_AUTH_FROM_NAME` (default: Backlog.fyi), `TASKER_EMAIL_AUTH_SMTP_HOST` (default:
  `smtp.protonmail.ch`), `TASKER_EMAIL_AUTH_SMTP_PORT` (default: 587),
  `TASKER_EMAIL_AUTH_SMTP_USERNAME`, `TASKER_EMAIL_AUTH_SMTP_PASSWORD`.
- Scheduling sender (calendar invites): `TASKER_EMAIL_SCHEDULING_FROM`,
  `TASKER_EMAIL_SCHEDULING_FROM_NAME`, `TASKER_EMAIL_SCHEDULING_SMTP_HOST`,
  `TASKER_EMAIL_SCHEDULING_SMTP_PORT`, `TASKER_EMAIL_SCHEDULING_SMTP_USERNAME`,
  `TASKER_EMAIL_SCHEDULING_SMTP_PASSWORD`.

## Feedback form (`FeedbackController` → `FeedbackService`, `POST /api/v1/feedback`)

- `TASKER_FEEDBACK_RECIPIENT` — mailbox that feedback submissions are forwarded to, over the **auth**
  sender (same SMTP credentials as login mail). When blank, falls back to the auth sender's own
  `from` address. Per-user rate-limited (`tasker.rate-limit.feedback`); the admin-facing email is
  English-only (not user-localised).

## Frontend (`VITE_*`, baked into the bundle at build time by the Gradle `buildFrontend` task)

No frontend env var is required for auth — Telegram login is a backend-driven OAuth redirect, so the
SPA just links to `/api/auth/telegram/start`. (`VITE_TELEGRAM_BOT_USERNAME` is no longer used now
that the iframe widget is gone.)

- `VITE_SUPPORT_EMAIL` — public support/contact address, read once in `src/config.ts` (defaults to
  `hello@backlog.fyi` when unset). Used for the import-error "Contact support" link and the contact
  lines in the static content pages, whose markdown carries a `{{SUPPORT_EMAIL}}` placeholder that
  `PolicyPage` substitutes.
- `VITE_ABUSE_EMAIL` — dedicated abuse-report address, same mechanism (defaults to
  `abuse@backlog.fyi`). Referenced via a `{{ABUSE_EMAIL}}` placeholder in the ToS, Privacy Policy,
  and FAQ markdown.
