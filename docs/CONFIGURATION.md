# Configuration reference

Environment variables read by the backend and baked into the frontend bundle. The rationale that
matters while writing code (the KEK warning, the multimodal gate, the tier-cap fallback, the
two-sender email split, the blocklist's loud rejection) stays in `CLAUDE.md` — this file is the
name/default/provenance reference.

## Instance (`AppProperties`)

- `TASKER_APP_BASE_URL` — the public URL users reach the instance at, e.g. `https://tasks.example.com`
  (no trailing slash). Magic links, invitations, the Telegram redirect URI, the API catalog and CORS
  are all built from it. Defaults to `https://backlog.fyi` outside `prod`; **required under `prod`**,
  where `ProductionConfigValidator` refuses to start without a valid value.

## Branding (`AppProperties`)

- `TASKER_APP_NAME` — the product name users see (default `Backlog.fyi`): emails, Telegram messages,
  the web UI and the planning assistant's introduction. 1–64 characters, none of `<>&"'{}` (it's
  inserted into HTML emails and `MessageFormat` patterns). Also the default sender name of both email
  senders.
- `TASKER_SUPPORT_EMAIL` — public contact address shown in the web UI (default `hello@backlog.fyi`).
- `TASKER_ABUSE_EMAIL` — abuse-report address referenced from the policy pages (default
  `abuse@backlog.fyi`).

All three reach the SPA at runtime through `GET /api/public/config`, so no rebuild is needed. The
message bundles and prompt templates say `@APP_NAME@` / `@APP_URL@` instead of the name and URL;
`BrandedMessageSource` and `PromptTemplateLoader` substitute the configured values.

**Icons and logos.** A public self-hosted instance needs its own icons (see `TRADEMARKS.md`). Put
replacement files — `favicon.ico`, `favicon-*.png`, `apple-touch-icon.png`, `og-image.png`, … under
the same names — in a directory and list it ahead of the bundled files:
`SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/branding/,classpath:/static/` (note the trailing `/`).
Files not in the directory keep coming from the bundle.

Not yet covered: `manifest.json` (the installed-app name), the `<noscript>`/meta tags in
`index.html` (the page title is replaced at runtime), and the static discovery files
(`llms.txt`, `robots.txt`, `sitemap.xml`, the external API's `SKILL.md`/`openapi.yaml`), which still
describe the hosted instance.

## Sign-in and registration (`AuthProperties`)

- `TASKER_REGISTRATION` — `open` (default) or `closed`. Closed means no new accounts: existing ones
  keep signing in and linking methods, local users (below) are still provisioned on first login, and
  an unknown Telegram or email login is refused (a magic link is only mailed to an address that
  already has an account; the endpoint still answers the same either way).
- `TASKER_DEMO_ENABLED` — the zero-registration sandbox (default `true`). Only offered while
  registration is open, since every visit creates an account.
- `TASKER_LOCAL_USERS` — operator-managed username/password logins, comma-separated
  `username:bcrypt-hash` entries (the format `htpasswd -nbBC 12 alice 'password'` prints). Usernames are
  case-insensitive (`a-z`, `0-9`, `.`, `_`, `@`, `-`). There is no sign-up, password change or reset:
  edit the list and restart. A changed password doesn't end sessions already signed in — use
  Settings → "Sign out other sessions". Invalid entries fail startup.
- `TASKER_LOCAL_USERS_FILE` — the same entries, one per line (`#` comments allowed), read from a file.
  Easier than the variable in Docker Compose, which would otherwise need every `$` in a hash
  doubled. Both sources may be combined; a username may appear only once.

The login page reads which methods are on from `GET /api/public/config` at runtime, so none of this
needs a frontend rebuild. The startup log prints a one-line summary of the enabled sign-in methods
and integrations, and warns when no sign-in method is available at all.

## Telegram (`TelegramAuthProperties`)

Both halves are optional; an instance without Telegram runs web-only.

- `TASKER_TELEGRAM_ENABLED` — whether to run the bot (default `false`, but `true` under `prod`). The
  bot only starts when `TASKER_TELEGRAM_BOT_TOKEN` is also set (`@ConditionalOnTelegramBot`), so
  leaving the token unset is enough to run without it.
- `TASKER_TELEGRAM_CLIENT_ID` — OIDC client id (bot id) for login; also the expected `aud` of the
  id_token. From BotFather → Bot Settings → Web Login.
- `TASKER_TELEGRAM_CLIENT_SECRET` — OIDC client secret (HTTP Basic credential for the token
  exchange). From the same BotFather screen.
- `TASKER_TELEGRAM_BOT_TOKEN` — bot messaging token (planning conversation); not used by login.
- `TASKER_TELEGRAM_BOT_USERNAME` — cosmetic / future use.

Telegram *login* is offered only when both the client id and secret are set.

## AI

- `TASKER_AI_API_KEY` — OpenRouter API key. Without it the app runs, but the weekly planning
  assistant and AI capture are unavailable (a warning is logged at startup).
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

## Metrics (`PrometheusAuthProperties`)

- `TASKER_PROMETHEUS_USERNAME` — Basic Auth username for `/actuator/prometheus` (default outside
  `prod`: `prometheus`).
- `TASKER_PROMETHEUS_PASSWORD` — Basic Auth password (default outside `prod`: `prometheus-dev`).

`prod` has no defaults: leaving either blank closes the endpoint (every request is refused) instead
of failing startup.

## Logging

Under `prod` the full log is JSON in a rolling `taskboard.log` in the working directory, with only
warnings on the console. Add the `container` profile (`SPRING_PROFILES_ACTIVE=prod,container`) to send
the full log to stdout instead and write no file — what `docker logs` and log shippers expect.

## Email

- `TASKER_EMAIL_ENABLED` — toggle the email integration (default: `false`). When false, both senders
  drop messages and log only that they did (no addresses, subjects or bodies — so no magic link
  either), and the login page hides email sign-in.
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

## Frontend

The SPA reads no `VITE_*` variables: everything instance-specific (login methods, name, contact
addresses) comes from `GET /api/public/config` at runtime, so one build — or one container image —
serves every instance. `VITE_SUPPORT_EMAIL` / `VITE_ABUSE_EMAIL` were replaced by
`TASKER_SUPPORT_EMAIL` / `TASKER_ABUSE_EMAIL` above.
