# Development

How to build, run and test Backlog.fyi locally. For running an instance, see
[`SELF-HOSTING.md`](SELF-HOSTING.md); for every setting, [`CONFIGURATION.md`](CONFIGURATION.md).

## Stack

- **Backend:** Kotlin 2.4, Spring Boot 4.1 on JVM 25, Spring Data JPA, Spring Security 7,
  Liquibase. Package `dev.itayp.tasker` (the project's original name).
- **Database:** PostgreSQL in production; in-memory H2 under the `dev` profile and in most tests.
- **Frontend:** React 19, TypeScript and Vite 8 in `tasker-frontend/`. The Gradle build bundles it
  into the JAR, and the backend serves it same-origin.
- **AI:** a thin client for the [OpenRouter](https://openrouter.ai) API; no AI framework.

## Prerequisites

- JDK 25
- Node 22 and npm
- Docker, only for the Postgres integration tests (they use Testcontainers)

## Running locally

```bash
./gradlew bootRun
```

The Gradle task:

1. runs `npm ci` and `npm run build` in `tasker-frontend/`, and copies the result into the
   backend's static resources;
2. starts the app with the `dev` profile, which uses in-memory H2, so there's no database to set
   up;
3. serves the SPA and the API on `http://localhost:8080`.

Sign in with **Start now — no sign-up**, which creates a sandbox account with a
few tutorial tasks.

To pass settings, copy `.env.example` (repo root) to `.env`, fill in what you need, and export it
before starting: `set -a; . ./.env; set +a; ./gradlew bootRun`. Nothing in it is required for
the board itself; the AI key is what turns on planning and AI capture.

### Frontend with hot reload

```bash
cd tasker-frontend
npm run dev
```

The Vite dev server runs on `http://localhost:5173` and proxies `/api` to the backend on `:8080`, so
keep `./gradlew bootRun` running alongside it. Under the dev server the login page also shows a
**Dev login (skip Telegram)** button, which signs in as a fixed dev user (it's compiled out of the
bundled build).

### Telegram (optional)

Needed only for the Telegram sign-in button and the bot (planning conversation, quick capture,
reminders).

1. Create a bot with [@BotFather](https://t.me/BotFather) (`/newbot`) and keep the token.
2. In BotFather → **Bot Settings → Web Login**, set the HTTPS host you'll serve from (Telegram won't
   redirect back to plain `http://localhost`), and copy the **Client ID** and **Client Secret**.
3. Set `TASKER_TELEGRAM_CLIENT_ID`, `TASKER_TELEGRAM_CLIENT_SECRET` (sign-in) and
   `TASKER_TELEGRAM_BOT_TOKEN`, `TASKER_TELEGRAM_BOT_USERNAME` (the bot), then restart.

Without the client ID and secret, the Telegram button shows a "temporarily unavailable" notice.

## Commands

Backend, from the repo root:

```bash
./gradlew bootRun                                          # run the app (dev profile)
./gradlew build                                            # compile, test, bundle the frontend
./gradlew test                                             # all backend tests
./gradlew test --tests "dev.itayp.tasker.SomeTest.method"  # a single test
```

Frontend, from `tasker-frontend/`:

```bash
npm run dev     # Vite dev server with hot reload
npm run build   # type-check and production build (Gradle runs this too)
npm run lint    # ESLint; zero warnings is the bar
npm run test    # Vitest
```

## Repo layout

```
src/main/kotlin/dev/itayp/tasker/   backend (controllers, services, planning, channels, …)
src/main/resources/                 config per profile, Liquibase changelog, prompts, emails, i18n bundles
tasker-frontend/                    the React SPA
Dockerfile, deploy/                 self-hosting image and Compose setup
docs/                               product spec, design notes, configuration and self-hosting guides
tools/                              asset-preparation scripts (see tools/README.md)
```

## Tests

Backend tests are JUnit 5:

- **Service unit tests** with Mockito (`mockito-kotlin`).
- **Controller slice tests** with `@WebMvcTest` and the real `SecurityConfiguration`, so
  authentication and CSRF are exercised.
- **Integration tests** with `@SpringBootTest` on a random port, against H2 under the `dev`
  profile.
- **Postgres integration tests** under the `prod` profile, against a Testcontainers PostgreSQL
  instance (`AbstractIntegrationTest.Initializer`). These need Docker.

Time-dependent code takes an injected `Clock`, so tests use `Clock.fixed(...)`.

Some integration tests talk to real external services (for example, `EmailIntegrationTest` sends
through SMTP). They skip unless their credentials are present: copy `.env.test.example` to
`.env.test` and fill in the sections you need.

Frontend tests use Vitest and React Testing Library in jsdom, co-located with the code as
`Foo.test.tsx`.

## Continuous integration

Every pull request to `main` runs [`.github/workflows/gradle.yml`](../.github/workflows/gradle.yml):

- `frontend`: `npm ci`, `lint`, `test` and `build` in `tasker-frontend/`.
- `build`: `./gradlew build` (compile, backend tests, bundled frontend), plus a check that the
  container image still builds.
- `dependency-submission`: submits the Gradle dependency graph for Dependabot.

A push to `main` also runs `release`, which publishes the JAR as a GitHub Release and the container
image to `ghcr.io/itaypk/taskboard`. It doesn't deploy anything.

## Running the JAR directly

The container image is the supported way to run an instance, but the JAR runs on its own too:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar taskboard.jar
```

Under `prod` it needs `TASKER_APP_BASE_URL`, the database (`TASKER_DB_URL`, `TASKER_DB_USERNAME`,
`TASKER_DB_PASSWORD`) and `TASKER_DATA_KEK`; everything else is optional. See
[`CONFIGURATION.md`](CONFIGURATION.md).

Health probes are public at `/actuator/health`, `/actuator/health/liveness` and
`/actuator/health/readiness`. Metrics are at `/actuator/prometheus`, behind HTTP Basic auth, and
only when `TASKER_PROMETHEUS_USERNAME` and `TASKER_PROMETHEUS_PASSWORD` are set. Grafana material
is in [`monitoring/`](monitoring).
