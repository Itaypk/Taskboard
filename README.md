# Tasker

An AI-powered weekly planner. Pin tasks to a backlog board; each week, a Telegram conversation walks you through which tasks to schedule, reads your Google Calendar, and writes the ones you pick back as time blocks.

Product spec: [`docs/SPEC.md`](docs/SPEC.md).

## Status

Incomplete, but functional:

- [x] Backlog CRUD (tasks, categories, tags) with a pinboard-style React frontend.
- [x] Passwordless auth — Telegram Login Widget + dev-login bypass for local iteration.
- [x] Session-cookie security, CSRF, Liquibase-managed schema.
- [x] Prometheus metrics (`/actuator/prometheus`), health probes (`/actuator/health/liveness`, `/actuator/health/readiness`), structured JSON logging via Logstash encoder.
- [x] Production config: PostgreSQL via env vars, secure session cookie, graceful shutdown.
- [x] Weekly planning conversation.
- [ ] Google Calendar integration

## Stack

- **Backend**: Kotlin 2.3 + Spring Boot 4.0 on JVM 25, Spring Data JPA, Spring Security 7, Liquibase.
- **Database**: Postgres in dev/prod, H2 for tests and in-memory dev.
- **Frontend**: React 19 + TypeScript + Vite 8, bundled into the backend at build time and served same-origin.
- **Auth**: Telegram Login Widget → HMAC verify → `HttpSession` cookie (`SameSite=Lax`, `HttpOnly`, `Secure` in prod). Prometheus scraper uses HTTP Basic Auth on a separate stateless filter chain.
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

## Telegram login setup (when you're ready)

1. `/newbot` with [@BotFather](https://t.me/BotFather), save the token.
2. `/setdomain` on your bot → point at the HTTPS host you'll serve from (Telegram won't attach the widget to bare `http://localhost`).
3. Export:
   ```bash
   export TASKER_TELEGRAM_BOT_TOKEN=...          # backend: verifies HMAC
   export TASKER_TELEGRAM_BOT_USERNAME=...       # backend + widget
   export VITE_TELEGRAM_BOT_USERNAME=...         # frontend build: renders the widget
   ```
4. Restart `./gradlew bootRun`. The login page will render the Telegram button instead of (in addition to, in dev) the dev-login button.

Without these vars, the dev-login button is the only way in, which is fine for local work.

## Repo layout

```
src/main/kotlin/dev/itayp/tasker/
  config/        Spring config (security, Prometheus auth, dev seed, telegram properties, time)
  controller/    REST controllers (auth, tasks, categories, tags)
  service/       Business logic (TelegramAuthService, UserAuthService, …)
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
| `TASKER_TELEGRAM_BOT_TOKEN` | Bot token for HMAC verification |
| `TASKER_TELEGRAM_BOT_USERNAME` | Bot username (cosmetic) |
| `TASKER_PROMETHEUS_USERNAME` | Basic Auth username for `/actuator/prometheus` |
| `TASKER_PROMETHEUS_PASSWORD` | Basic Auth password for `/actuator/prometheus` |

Startup fails fast if any of the database or Prometheus credentials are absent (no fallback defaults in the prod profile).

### Observability endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `/actuator/health` | public | Overall health (DB connectivity etc.) |
| `/actuator/health/liveness` | public | Liveness probe |
| `/actuator/health/readiness` | public | Readiness probe |
| `/actuator/prometheus` | Basic Auth (`PROMETHEUS` role) | Prometheus scrape target |

## Testing

- **Unit tests** — Mockito + JUnit 5 for services (`BacklogTaskServiceTest`, `TelegramAuthServiceTest`, `UserAuthServiceTest`).
- **Controller slice tests** — `@WebMvcTest` + `SecurityConfiguration` so auth + CSRF behavior is exercised (`AuthControllerTest`, `BacklogTaskControllerTest`).
- **Integration tests** — `@SpringBootTest(RANDOM_PORT)` with `TestRestTemplate` for real session-cookie reuse, CSRF enforcement, and prod-profile gating (`SecurityIntegrationTest`).
- **Postgres integration tests** — `AbstractIntegrationTest.Initializer` starts a TestContainers `PostgreSQLContainer` and wires it into the Spring context. Tests run under `@ActiveProfiles("prod")` and exercise JPA and the health endpoints against a real database (`PostgresIntegrationTest`, `SecurityIntegrationProdProfileTest`).

All time-dependent code takes an injected `Clock`, so tests can use `Clock.fixed(...)` and assert deterministic behavior.

### Running the Tests

For specific tests:
```bash
./gradlew --info test --tests dev.itayp.tasker.channel.email.EmailIntegrationTest
```
