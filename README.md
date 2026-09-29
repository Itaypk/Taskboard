# Backlog.fyi

An AI-powered weekly planner. Pin tasks to a backlog board; each week, a planning conversation (Telegram or web) walks you through which tasks to schedule, and turns the ones you agree to into calendar time blocks, delivered as emailed calendar invitations.

Hosted at [backlog.fyi](https://backlog.fyi). The source is open (AGPL-3.0-only) so you can read it, audit how it handles your data, and run it yourself. See [License](#license) and [Contributing](#contributing).

Product spec: [`docs/SPEC.md`](docs/SPEC.md).

## Status

Incomplete, but functional:

- [x] Backlog CRUD (tasks, categories, tags) with a pinboard-style React frontend.
- [x] Passwordless auth, decoupled from Telegram — Telegram Login Widget, email magic-link, demo sandbox, and a dev-login bypass for local iteration. Multiple login methods per account ("connected accounts") via an `auth_identities` model. Login no longer requires a Telegram account.
- [x] Session-cookie security, CSRF, Liquibase-managed schema.
- [x] Prometheus metrics (`/actuator/prometheus`), health probes (`/actuator/health/liveness`, `/actuator/health/readiness`), structured JSON logging via Logstash encoder.
- [x] Production config: PostgreSQL via env vars, secure session cookie, graceful shutdown.
- [x] Weekly planning conversation.
- [ ] Google Calendar integration

## Stack

- **Backend**: Kotlin 2.3 + Spring Boot 4.1 on JVM 25, Spring Data JPA, Spring Security 7, Liquibase.
- **Database**: Postgres in dev/prod, H2 for tests and in-memory dev.
- **Frontend**: React 19 + TypeScript + Vite 8, bundled into the backend at build time and served same-origin.
- **Auth**: multiple providers resolved through an `auth_identities` table (Telegram HMAC, email magic-link, demo, dev) → `HttpSession` cookie (`SameSite=Lax`, `HttpOnly`, `Secure` in prod). `TaskerPrincipal` carries only `userId`, so the session layer is provider-agnostic. Prometheus scraper uses HTTP Basic Auth on a separate stateless filter chain. See [`docs/archive/AUTH-DECOUPLING.md`](docs/archive/AUTH-DECOUPLING.md).
- **Observability**: Micrometer + Prometheus registry; health probes for liveness/readiness; structured JSON log rotation via Logstash encoder (prod profile).

## Getting started

### Prerequisites

- JDK 25
- Node 20+ and npm (for the frontend build)
- Docker (for the Postgres container, started automatically via `spring-boot-docker-compose`)

### Run it

```bash
./gradlew bootRun
```

With environment variables:

```bash
export $(cat .env | xargs) && ./gradlew bootRun
```

That's the whole flow in dev:

1. Gradle runs `npm ci` and `npm run build` for the frontend.
2. The built frontend is copied into `src/main/resources/static/`.
3. The `bootRun` Gradle task passes `--spring.profiles.active=dev`, so the app uses the H2 in-memory database. `spring-boot-docker-compose` also starts Postgres from `compose.yaml`, but the dev profile does not use it.
4. Spring Boot serves the SPA and the API on `http://localhost:8080`.

Open `http://localhost:8080`. Click **Dev login (skip Telegram)** to sign in as the deterministic dev user — this works under the `dev` profile without any BotFather setup.

### Frontend iteration with HMR

If you want Vite HMR, from `tasker-frontend/`:

```bash
npm run dev
```

You'll need a dev proxy to forward `/api/**` to `:8080` (not set up by default; the bundled-into-backend flow is the primary dev loop today).

## Telegram login setup (optional)

Telegram is no longer required to sign in — email magic-link, the demo sandbox, and (in dev) the dev-login button all work without it. Set this up when you want the Telegram login button (an OAuth2/OIDC redirect, [Telegram Login docs](https://core.telegram.org/widgets/login)) and the Telegram-driven weekly planning conversation.


1. `/newbot` with [@BotFather](https://t.me/BotFather), save the token.
2. In BotFather → **Bot Settings → Web Login**, set the HTTPS host you'll serve from (Telegram won't redirect back to bare `http://localhost`) and copy the **Client ID** and **Client Secret**.
3. Export:
   ```bash
   export TASKER_TELEGRAM_CLIENT_ID=...          # backend: OIDC client id (bot id) + expected id_token aud
   export TASKER_TELEGRAM_CLIENT_SECRET=...      # backend: OIDC client secret (token-exchange credential)
   export TASKER_TELEGRAM_BOT_TOKEN=...          # backend: bot messaging (planning conversation)
   export TASKER_TELEGRAM_BOT_USERNAME=...       # backend (cosmetic)
   ```
4. Restart `./gradlew bootRun`. The login page's Telegram button kicks off the OAuth redirect; without the Client ID/Secret it shows a "temporarily unavailable" notice and you fall back to the dev-login button.

Without these vars, the dev-login button (and the demo sandbox) get you in for local work; email magic-link login also works once email is configured.

## Repo layout

```
src/main/kotlin/dev/itayp/tasker/
  config/        Spring config (security, Prometheus auth, dev seed, telegram properties, time)
  controller/    REST controllers (auth, tasks, categories, tags)
  service/       Business logic (TelegramOidcService, UserAuthService, …)
  security/      TaskerPrincipal + SessionAuthenticator
  jpa/           JPA entities
  repository/    Spring Data repositories
  model/         API DTOs (requests, responses, domain enums)
src/main/resources/
  application.yaml          base config (shared across all profiles)
  application-dev.yaml      H2 datasource
  application-prod.yaml     PostgreSQL datasource + secure cookie (env-var driven)
  logback-spring.xml        plain console in dev; rolling JSON files in prod
  db/changelog/             Liquibase master + changesets
tasker-frontend/src/
  auth/          AuthProvider, LoginPage, auth API client
  components/    PostItNote, TaskDrawer, SettingsModal
  App.tsx, api.ts, types.ts, …
docs/SPEC.md     Product spec
compose.yaml     Postgres for local dev (started automatically, not used by dev profile)
```

## Common commands

Backend:

```bash
./gradlew bootRun                                          # run the app
./gradlew build                                            # compile + test + bundle frontend
./gradlew test                                             # all tests
./gradlew test --tests "dev.itayp.tasker.SomeTest.method"  # single test
```

Frontend (from `tasker-frontend/`):

```bash
npm run dev     # Vite dev server
npm run build   # tsc -b && vite build (also runs via Gradle)
npm run lint
npm run test    # vitest run
```

## Production deployment

The app is a self-contained fat JAR deployed as a systemd service. Start it with the `prod` profile active:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar taskboard.jar
```

Required environment variables:

| Variable | Purpose |
|---|---|
| `TASKER_DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/taskboard` |
| `TASKER_DB_USERNAME` | Postgres user |
| `TASKER_DB_PASSWORD` | Postgres password |
| `TASKER_DATA_KEK` | base64-encoded 32-byte key wrapping per-user DEKs for at-rest encryption. **Losing it loses all encrypted data.** Generate with `openssl rand -base64 32` |
| `TASKER_TELEGRAM_CLIENT_ID` | OIDC client id (bot id) for Telegram login; expected id_token `aud` |
| `TASKER_TELEGRAM_CLIENT_SECRET` | OIDC client secret for the Telegram token exchange |
| `TASKER_TELEGRAM_BOT_TOKEN` | Bot messaging token (planning conversation) |
| `TASKER_TELEGRAM_BOT_USERNAME` | Bot username (cosmetic) |
| `TASKER_PROMETHEUS_USERNAME` | Basic Auth username for `/actuator/prometheus` |
| `TASKER_PROMETHEUS_PASSWORD` | Basic Auth password for `/actuator/prometheus` |

Startup fails fast if any of the database, data-encryption, or Prometheus credentials are absent (no fallback defaults in the prod profile).

**Email** (optional, for login magic links and calendar invites) is split into two independent SMTP senders — `auth` (login/register/verification) and `scheduling` (calendar invites). Enable with `TASKER_EMAIL_ENABLED=true` and set the `TASKER_EMAIL_AUTH_*` / `TASKER_EMAIL_SCHEDULING_*` variables (from address + SMTP host/port/username/password per sender). Full list in [`CLAUDE.md`](CLAUDE.md). When disabled, both senders log instead of sending (the magic link is printed to the log).

### Observability endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `/actuator/health` | public | Overall health (DB connectivity etc.) |
| `/actuator/health/liveness` | public | Liveness probe |
| `/actuator/health/readiness` | public | Readiness probe |
| `/actuator/prometheus` | Basic Auth (`PROMETHEUS` role) | Prometheus scrape target |

## Testing

Backend:

- **Unit tests** — Mockito + JUnit 5 for services (`BacklogTaskServiceTest`, `TelegramOidcServiceTest`, `UserAuthServiceTest`).
- **Controller slice tests** — `@WebMvcTest` + `SecurityConfiguration` so auth + CSRF behavior is exercised (`AuthControllerTest`, `BacklogTaskControllerTest`).
- **Integration tests** — `@SpringBootTest(RANDOM_PORT)` with `TestRestTemplate` for real session-cookie reuse, CSRF enforcement, and prod-profile gating (`SecurityIntegrationTest`).
- **Postgres integration tests** — `AbstractIntegrationTest.Initializer` starts a TestContainers `PostgreSQLContainer` and wires it into the Spring context. Tests run under `@ActiveProfiles("prod")` and exercise JPA and the health endpoints against a real database (`PostgresIntegrationTest`, `SecurityIntegrationProdProfileTest`).

All time-dependent code takes an injected `Clock`, so tests can use `Clock.fixed(...)` and assert deterministic behavior.

### Running the Tests

For specific tests:
```bash
./gradlew --info test --tests dev.itayp.tasker.channel.email.EmailIntegrationTest
```

Frontend (`tasker-frontend/`):

- **Vitest** + **React Testing Library** + `@testing-library/jest-dom`, jsdom environment. Config lives in `vite.config.ts`'s `test` field; the setup file is `src/setupTests.ts`. Test files are co-located next to the file under test as `Foo.test.tsx` — see `src/NotFoundPage.test.tsx` for the baseline shape. Coverage is minimal so far; add tests alongside new/changed code as you go.

```bash
npm run test    # vitest run
```

## Continuous integration

Every pull request to `main` runs [`.github/workflows/gradle.yml`](.github/workflows/gradle.yml):

- `frontend` job — `npm ci`, `npm run lint`, `npm run test`, `npm run build`, all in `tasker-frontend/`.
- `build` job — `./gradlew build` (compiles, runs backend tests, bundles the frontend into the JAR).
- `dependency-submission` job — submits the Gradle dependency graph for Dependabot alerts.

## Self-hosting

Possible, but not turnkey yet: you need Postgres, a Telegram bot (optional for web-only use), an OpenRouter key for the AI features, and SMTP accounts for email. See "Production deployment" above and [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md). Easier self-hosting (a Docker Compose setup, a self-hosting guide) is tracked in [issue #271](https://github.com/Itaypk/Taskboard/issues/271).

## License

Copyright (C) 2026 Itay Polack-Gadassi.

Licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`). If you run a modified version as a network service, the AGPL requires you to offer its users the corresponding source.

## Contributing

This is a solo project and pull requests are not accepted. Bug reports are welcome; see [`CONTRIBUTING.md`](CONTRIBUTING.md) for details.
