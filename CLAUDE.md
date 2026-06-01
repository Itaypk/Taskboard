# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**Backlog.fyi** is an AI-powered weekly planner: a task backlog plus a Telegram-driven weekly planning conversation that reads Google Calendar and writes agreed tasks back as time blocks. The internal codebase package is `dev.itayp.tasker` (historical name); the public-facing product name is **Backlog.fyi** — use that name in any user-visible copy (emails, UI strings, etc.). The product vision (core loop, task fields, integrations, non-goals) lives in `docs/SPEC.md` — read it before making design decisions. 

Current state: the backlog CRUD (tasks, categories, tags) is implemented end-to-end, with Telegram Login Widget + dev-login session auth wired up. The weekly planning conversation, and LLM-driven planner are working, with email-invitation integration. No Google Calendar integration yet.

This is a non-commercial solo side project. It is currently running in production, but only serves a handful of beta users.

## Working on the project

A few things to consider while working on the project:
- As a solo project, we have full responsibility and full knowledge - do not ignore pre-existing issues. If you notice an issue that might be a bug, surface it in your response.
- The number of active users is still very low, and they are all aware of the beta status. Consider the option of starting fresh (wiping the prod DB and re-seeding) rather than writing a complex
  migration. This overrides the additive-only rule, but only when we explicitly decide to reset.
- On production, the app runs as a single instance on an Ubuntu VPS. Short downtime is acceptable.
- Deployment: legacy deploy script (./deploy.sh) was recently replaced with an Ansible playbook, maintained on a different repo. Do not deploy yourself unless specifically asked for.

## Repo layout

- `src/` — Kotlin/Spring Boot backend (package `dev.itayp.tasker`). Entry point: `src/main/kotlin/dev/itayp/tasker/TaskBoardApplication.kt`.
  - `config/` — Spring config (`SecurityConfiguration`, `DevDataInitializer`, `TelegramAuthProperties`, `PrometheusAuthProperties`, `TimeConfiguration`, `DevAuthConstants`).
  - `controller/` — REST controllers. `AuthController` handles Telegram login + `/me`; `DevAuthController` is `@Profile("dev")` only.
  - `service/` — `TelegramAuthService` (HMAC verify), `UserAuthService` (login-or-register), per-resource services.
  - `security/` — `TaskerPrincipal` (UUID user id) + `SessionAuthenticator` (creates session on successful auth).
  - `jpa/`, `repository/`, `model/` — entities, Spring Data repositories, and API DTOs.
  - `resources/db/changelog/` — Liquibase changelog master + changesets.
- `tasker-frontend/` — React + TypeScript + Vite app. **Bundled into the backend** at build time: the Gradle `buildFrontend` task runs `npm run build`, and `processResources` copies `tasker-frontend/dist/` into `src/main/resources/static/`. At runtime everything is served same-origin.
- `compose.yaml` — Postgres service for local dev. `spring-boot-docker-compose` starts it automatically on `bootRun`.
- `docs/SPEC.md` — product spec (source of truth for intent).
- `tools/` — ad-hoc scripts (currently just an image background-remover).

## Web environment note

When running via **claude.ai/code** (the web environment), the sandbox does not have the project's JVM 25 toolchain installed. Do not attempt to compile, run tests, or start the server — those commands will fail. Instead, write the code, commit, and push; then wait for CI results to confirm correctness.

## Common commands

Backend (run from repo root):
- `./gradlew bootRun` — run the Spring Boot app with `--spring.profiles.active=dev` (set in the Gradle task); auto-starts Postgres via compose and bundles the frontend as a side effect of `processResources`.
- `./gradlew build` — compile + test + bundle frontend.
- `./gradlew test` — run all tests.
- `./gradlew test --tests "dev.itayp.tasker.SomeTest.someMethod"` — run a single test.

Frontend (run from `tasker-frontend/`, only needed for fast iteration with HMR):
- `npm run dev` — Vite dev server on `:5173`. You'll need a reverse proxy or CORS for it to talk to the backend on `:8080`; in most workflows it's simpler to just `./gradlew bootRun` and edit through the bundled build.
- `npm run build` — `tsc -b && vite build` (also runs via Gradle).
- `npm run lint` — ESLint.

## Auth model (important — affects every new endpoint)

There are three independent `SecurityFilterChain` beans:

1. **`h2ConsoleFilterChain` (`@Order(0)`)** - applies for the `dev` profile only; allows full access to the `/h2-console` endpoint.
2. **`prometheusFilterChain` (`@Order(1)`)** — matches only `/actuator/prometheus`. Stateless HTTP Basic Auth; credentials come from `PrometheusAuthProperties` (`TASKER_PROMETHEUS_USERNAME` / `TASKER_PROMETHEUS_PASSWORD`). CSRF disabled.
3. **`securityFilterChain` (`@Order(2)`)** — everything else. Session-based with a `SameSite=Lax`, `HttpOnly`, `Secure` (prod) cookie (`SESSION`), 30-day rolling timeout. 

Session chain details:
- **CSRF** via `CookieCsrfTokenRepository.withHttpOnlyFalse()` — mutating requests must echo the `XSRF-TOKEN` cookie value as the `X-XSRF-TOKEN` header. Login endpoints (`/api/auth/telegram`, `/api/auth/dev-login`) are exempt because they create the session. Frontend `api.ts` handles this automatically.
- **Login paths**:
  1. `POST /api/auth/telegram` — validates Telegram Login Widget HMAC payload, upserts a `UserEntity` by `telegram_id`, seeds default categories for new users.
  2. `POST /api/auth/dev-login` — `@Profile("dev")` only, logs in as a deterministic UUID (`UUID.nameUUIDFromBytes("tasker-dev-user".toByteArray())`). Used by `DevDataInitializer` on startup so H2 always has a usable dev user.
- **Controllers inject `@AuthenticationPrincipal principal: TaskerPrincipal`** and use `principal.userId: UUID`. Never hardcode user ids.
- **Writing a new session**: call `SessionAuthenticator.authenticate(principal, request, response)`. It saves the context via `HttpSessionSecurityContextRepository.saveContext` — **this call is mandatory** in Spring Security 7 or the session cookie won't be issued.
- **401 vs 403**: `exceptionHandling { authenticationEntryPoint = HttpStatusEntryPoint(UNAUTHORIZED) }` means unauth requests to `/api/**` return 401 JSON (the SPA listens for 401 and clears auth state). Missing/invalid CSRF returns 403.
- **Session persistence**: sessions are stored in the `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` tables via `spring-session-jdbc`, so they survive application restarts. `spring.session.jdbc.initialize-schema: never` — the schema is owned by Liquibase (changeset `002-spring-session.xml`). Every request updates `last_access_time`, so the 30-day TTL rolls forward for active users and only idle sessions expire. Expired rows are GC'd by Spring Session's internal scheduled cleanup.
- **CORS**: allow access from common localhost ports (NPM default and IntelliJ local files), and the base production URL (read from configuration)

## Data model

- **UUIDs everywhere**: user ids, task ids, category/tag ids. Stored as `UUID` columns with foreign keys to `users(id)`.
- Liquibase runs on startup against H2 (dev/test) and Postgres (prod). `spring.jpa.hibernate.ddl-auto: validate` — Hibernate does **not** manage schema. The master changelog is `src/main/resources/db/changelog/db.changelog-master.xml`; additional changesets live under `src/main/resources/db/changelog/changesets/` and are wired in via `<include>`.
- **The app is deployed against a real Postgres database, so migrations are additive only.** Never edit a previously-applied changeset (including `id="1"`) — add a new changeset with the next integer id instead. Renames, column-type changes, and drops must be done through new changesets that preserve existing data.
- **Large text columns**: use `type="LONGVARCHAR"` in Liquibase and `@Column(columnDefinition = "TEXT")` in the JPA entity. Liquibase maps `LONGVARCHAR` → `TEXT` in Postgres and `VARCHAR` in H2; both satisfy Hibernate's schema validation for a `String` field. Do **not** use `type="TEXT"` in Liquibase — H2 maps that to CLOB, which fails validation. `context_block` (changeset 1) is a legacy exception: it's `VARCHAR(4096)` and has no `columnDefinition`.

## Stack notes that affect how you write code

- **JVM 25** via Gradle toolchain (`build.gradle.kts`). `HELP.md` notes a past downgrade to 24 for Kotlin compat; check Kotlin 2.3.20's supported JVM targets before changing Java versions.
- **Kotlin Spring plugins**: `kotlin-spring` (auto-opens Spring-managed classes) and `kotlin-jpa` with `allOpen` for `@Entity`, `@MappedSuperclass`, `@Embeddable`. Don't mark JPA entities `open` manually.
- **Jackson**: uses `tools.jackson.module:jackson-module-kotlin` (Jackson 3.x, `tools.jackson` package), not `com.fasterxml.jackson.*`. Import accordingly.
- **Handlebars**: uses Handlebars.java for AI prompts and email templates. Prompt templates live in `src/main/resources/prompts/`, emails under `src/main/resources/emails`. 
- **Spring Boot 4.0.x**: several starter artifacts moved. Notable: `TestRestTemplate` lives in `org.springframework.boot.resttestclient` and requires `spring-boot-restclient` + `spring-boot-resttestclient` as `testImplementation` (already declared).
- **LLM**: Spring AI is **not** the chosen approach. Use a thin, hand-rolled abstraction over the Claude API when the planner is built.
- **Databases**: H2 in-memory for dev (`application-dev.yaml`) and unit/slice tests. Postgres for prod (`application-prod.yaml`). Integration tests under `@ActiveProfiles("prod")` spin up a real Postgres instance via TestContainers (`AbstractIntegrationTest.Initializer`). Config is split across `application.yaml` (base), `application-dev.yaml`, and `application-prod.yaml`.
- **Compiler flags**: `-Xjsr305=strict` (JSR-305 nullability → errors) and `-Xannotation-default-target=param-property` (Kotlin 2.x annotation target default). Keep nullability annotations honest.

## Frontend specifics

- React 19, Vite 8, TypeScript ~6.0. Routing via `react-router-dom` v7 (`BrowserRouter` in `main.tsx`); the SPA fallback for non-root client routes (e.g. `/terms`, `/privacy`) lives in `controller/SpaForwardController.kt` — add new top-level routes there too.
- **Styling: co-located CSS Modules for component-specific styles.** New components add a sibling `Foo.module.css` and import it as `import styles from './Foo.module.css'`; reference classes via `styles.title` / `styles.demoBtn`. Use camelCase keys. `src/index.css` is reserved for design tokens (CSS custom properties), base element styles, and genuinely shared utilities (`.link-btn`, etc.) — don't add new component-specific rules there. See `auth/LoginPage.module.css` and `auth/PolicyPage.module.css` for the pattern.
- Auth-aware shell in `src/App.tsx`: `AuthProvider` (in `src/auth/AuthContext.tsx`) runs `GET /api/auth/me` on mount; `LoginPage` renders the Telegram Login Widget plus a dev-login button gated on `import.meta.env.DEV`.
- `src/api.ts` wraps `fetch` with `credentials: 'include'`, echoes the XSRF cookie as `X-XSRF-TOKEN` on mutating requests, and dispatches an `auth:unauthenticated` event on 401 so `AuthContext` can flip to the login page.
- Required env vars (set in `tasker-frontend/.env` or via shell):
  - `VITE_TELEGRAM_BOT_USERNAME` — bot username for the widget script.
- Backend env vars (read via `TelegramAuthProperties`):
  - `TASKER_TELEGRAM_BOT_TOKEN` — used to verify the widget HMAC.
  - `TASKER_TELEGRAM_BOT_USERNAME` — cosmetic / future use.
- Backend env vars (read via `PrometheusAuthProperties`) — **required in prod, dev defaults apply otherwise**:
  - `TASKER_PROMETHEUS_USERNAME` — Basic Auth username for `/actuator/prometheus` (default: `prometheus`).
  - `TASKER_PROMETHEUS_PASSWORD` — Basic Auth password for `/actuator/prometheus` (default: `prometheus-dev`).
- Backend env vars for the production database (required when running with `prod` profile):
  - `TASKER_DB_URL` — JDBC URL, e.g. `jdbc:postgresql://host:5432/taskboard`.
  - `TASKER_DB_USERNAME` / `TASKER_DB_PASSWORD` — Postgres credentials.
- Backend env var for at-rest data encryption (required in prod):
  - `TASKER_DATA_KEK` — base64-encoded 32-byte key. Wraps per-user DEKs that encrypt task titles, descriptions, LLM messages, user settings, etc. **Losing this key permanently loses all encrypted data.** Generate with `openssl rand -base64 32`; store the prod value offline (e.g. password manager), and keep it out of any archive that also includes DB dumps. Dev/test fall back to a checked-in placeholder key — never reuse that for prod.
- Backend env vars for the email integration:
  - `TASKER_EMAIL_ENABLED` - toggle email integration (default: false).
  - `TASKER_EMAIL_FROM` - email address to use in the "from" field.
  - `TASKER_EMAIL_FROM_NAME` - name to use in the "from" field.
  - `TASKER_EMAIL_SMTP_HOST` - SMTP host (default: smtp.protonmail.ch).
  - `TASKER_EMAIL_SMTP_PORT` - SMTP port (default: 587).
  - `TASKER_EMAIL_SMTP_USERNAME` - SMTP username (this is the email we use).
  - `TASKER_EMAIL_SMTP_PASSWORD` - SMTP token, used as password.

## Internationalization

Use the user's selected language and locale in the various communication channels (Telegram, email). We are using Spring's `MessageSource`, with message bundles (on `src/main/resources`).
The web UI is currently English-only. Emails and Telegram communications are fully localized — keep it that way. 

## Logging

- A rule of thumb for useful log volume: 1-2 INFO + 0-4 DEBUG logs for a mutating operation, 0-2 DEBUG logs for a read-only operation. We might revise that if the number of active users go up.
- Within user context, user IDs are automatically available in the logs through MDC. When relevant, add user IDs explicitly for operations outside the user's context, such as scheduled background tasks.

## Privacy

- Sensitive user data is encrypted at rest. When adding new fields, assess their sensitivity with the user to decide whether they require encryption or not.
- **No sensitive user data in logs**: UUIDs (user IDs, task IDs, session IDs) are fine to log. Task titles, descriptions, notes, and any other sensitive user-authored content are not.

## Testing patterns

- Service unit tests: `@ExtendWith(MockitoExtension::class)` + `mockito-kotlin`. See `BacklogTaskServiceTest`, `UserAuthServiceTest`.
- Controller slice tests: `@WebMvcTest(SomeController::class)` + `@Import(SecurityConfiguration::class)`. Authenticate with `SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))))`, and add `.with(csrf())` for non-GET requests. See `BacklogTaskControllerTest`, `AuthControllerTest`.
- Integration tests: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureTestRestTemplate` + `@ActiveProfiles("dev"|"prod")`. See `SecurityIntegrationTest` — covers cookie reuse, CSRF enforcement, and prod-profile gating of dev-login.
- **Postgres integration tests**: use `@ContextConfiguration(initializers = [AbstractIntegrationTest.Initializer::class])` together with `@ActiveProfiles("prod")`. The `Initializer` starts a shared TestContainers `PostgreSQLContainer` and injects its coordinates into the Spring environment before the context refreshes. See `PostgresIntegrationTest` — validates health/liveness endpoints and JPA CRUD against real Postgres. `SecurityIntegrationProdProfileTest` also uses the initializer because the prod profile requires a live datasource.
- Time-dependent code (`TelegramAuthService`, `UserAuthService`) takes a `Clock` — tests inject `Clock.fixed(...)`.
