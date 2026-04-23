# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Tasker is an AI-powered weekly planner: a task backlog plus a Telegram-driven weekly planning conversation that reads Google Calendar and writes agreed tasks back as time blocks. The product vision (core loop, task fields, integrations, non-goals) lives in `docs/SPEC.md` — read it before making design decisions. `docs/SPEC - Deprecated.md` is superseded; don't use it.

Current state: the backlog CRUD (tasks, categories, tags) is implemented end-to-end, with Telegram Login Widget + dev-login session auth wired up. The weekly planning conversation, Google Calendar integration, and LLM-driven planner are not yet built.

## Repo layout

- `src/` — Kotlin/Spring Boot backend (package `dev.itayp.tasker`). Entry point: `src/main/kotlin/dev/itayp/tasker/TaskBoardApplication.kt`.
  - `config/` — Spring config (`SecurityConfiguration`, `DevDataInitializer`, `TelegramAuthProperties`, `TimeConfiguration`, `DevAuthConstants`).
  - `controller/` — REST controllers. `AuthController` handles Telegram login + `/me`; `DevAuthController` is `@Profile("dev")` only.
  - `service/` — `TelegramAuthService` (HMAC verify), `UserAuthService` (login-or-register), per-resource services.
  - `security/` — `TaskerPrincipal` (UUID user id) + `SessionAuthenticator` (creates session on successful auth).
  - `jpa/`, `repository/`, `model/` — entities, Spring Data repositories, and API DTOs.
  - `resources/db/changelog/` — Liquibase changelog master + changesets.
- `tasker-frontend/` — React + TypeScript + Vite app. **Bundled into the backend** at build time: the Gradle `buildFrontend` task runs `npm run build`, and `processResources` copies `tasker-frontend/dist/` into `src/main/resources/static/`. At runtime everything is served same-origin.
- `compose.yaml` — Postgres service for local dev. `spring-boot-docker-compose` starts it automatically on `bootRun`.
- `docs/SPEC.md` — product spec (source of truth for intent).
- `tools/` — ad-hoc scripts (currently just an image background-remover).

## Common commands

Backend (run from repo root):
- `./gradlew bootRun` — run the Spring Boot app (auto-starts Postgres via compose; also builds + bundles the frontend as a side effect of `processResources`).
- `./gradlew build` — compile + test + bundle frontend.
- `./gradlew test` — run all tests.
- `./gradlew test --tests "dev.itayp.tasker.SomeTest.someMethod"` — run a single test.

Frontend (run from `tasker-frontend/`, only needed for fast iteration with HMR):
- `npm run dev` — Vite dev server on `:5173`. You'll need a reverse proxy or CORS for it to talk to the backend on `:8080`; in most workflows it's simpler to just `./gradlew bootRun` and edit through the bundled build.
- `npm run build` — `tsc -b && vite build` (also runs via Gradle).
- `npm run lint` — ESLint.

## Auth model (important — affects every new endpoint)

- **Session-based** Spring Security with a `SameSite=Lax`, `HttpOnly` cookie (`JSESSIONID`), 14-day timeout.
- **CSRF** via `CookieCsrfTokenRepository.withHttpOnlyFalse()` — mutating requests must echo the `XSRF-TOKEN` cookie value as the `X-XSRF-TOKEN` header. Login endpoints (`/api/auth/telegram`, `/api/auth/dev-login`) are exempt because they create the session. Frontend `api.ts` handles this automatically.
- **Login paths**:
  1. `POST /api/auth/telegram` — validates Telegram Login Widget HMAC payload, upserts a `UserEntity` by `telegram_id`, seeds default categories for new users.
  2. `POST /api/auth/dev-login` — `@Profile("dev")` only, logs in as a deterministic UUID (`UUID.nameUUIDFromBytes("tasker-dev-user".toByteArray())`). Used by `DevDataInitializer` on startup so H2 always has a usable dev user.
- **Controllers inject `@AuthenticationPrincipal principal: TaskerPrincipal`** and use `principal.userId: UUID`. Never hardcode user ids.
- **Writing a new session**: call `SessionAuthenticator.authenticate(principal, request, response)`. It saves the context via `HttpSessionSecurityContextRepository.saveContext` — **this call is mandatory** in Spring Security 7 or the session cookie won't be issued.
- **401 vs 403**: `exceptionHandling { authenticationEntryPoint = HttpStatusEntryPoint(UNAUTHORIZED) }` means unauth requests to `/api/**` return 401 JSON (the SPA listens for 401 and clears auth state). Missing/invalid CSRF returns 403.

## Data model

- **UUIDs everywhere**: user ids, task ids, category/tag ids. Stored as `UUID` columns with foreign keys to `users(id)`.
- Liquibase runs on startup against H2 (dev/test) and Postgres (prod). `spring.jpa.hibernate.ddl-auto: validate` — Hibernate does **not** manage schema. All schema changes go through Liquibase changesets in `src/main/resources/db/changelog/changesets/`.
- **The repo is pre-release and currently allows breaking changes straight into changeset `001`.** Once a real production DB exists, switch to additive migrations.

## Stack notes that affect how you write code

- **JVM 25** via Gradle toolchain (`build.gradle.kts`). `HELP.md` notes a past downgrade to 24 for Kotlin compat; check Kotlin 2.3.20's supported JVM targets before changing Java versions.
- **Kotlin Spring plugins**: `kotlin-spring` (auto-opens Spring-managed classes) and `kotlin-jpa` with `allOpen` for `@Entity`, `@MappedSuperclass`, `@Embeddable`. Don't mark JPA entities `open` manually.
- **Jackson**: uses `tools.jackson.module:jackson-module-kotlin` (Jackson 3.x, `tools.jackson` package), not `com.fasterxml.jackson.*`. Import accordingly.
- **Spring Boot 4.0.x**: several starter artifacts moved. Notable: `TestRestTemplate` lives in `org.springframework.boot.resttestclient` and requires `spring-boot-restclient` + `spring-boot-resttestclient` as `testImplementation` (already declared).
- **LLM**: Spring AI is **not** the chosen approach. Use a thin, hand-rolled abstraction over the Claude API when the planner is built.
- **Databases**: Postgres in dev/prod, H2 for in-memory dev + tests. Config is explicit in `application.yaml`.
- **Compiler flags**: `-Xjsr305=strict` (JSR-305 nullability → errors) and `-Xannotation-default-target=param-property` (Kotlin 2.x annotation target default). Keep nullability annotations honest.

## Frontend specifics

- React 19, Vite 8, TypeScript ~6.0.
- Auth-aware shell in `src/App.tsx`: `AuthProvider` (in `src/auth/AuthContext.tsx`) runs `GET /api/auth/me` on mount; `LoginPage` renders the Telegram Login Widget plus a dev-login button gated on `import.meta.env.DEV`.
- `src/api.ts` wraps `fetch` with `credentials: 'include'`, echoes the XSRF cookie as `X-XSRF-TOKEN` on mutating requests, and dispatches an `auth:unauthenticated` event on 401 so `AuthContext` can flip to the login page.
- Required env vars (set in `tasker-frontend/.env` or via shell):
  - `VITE_TELEGRAM_BOT_USERNAME` — bot username for the widget script.
- Backend env vars (read via `TelegramAuthProperties`):
  - `TASKER_TELEGRAM_BOT_TOKEN` — used to verify the widget HMAC.
  - `TASKER_TELEGRAM_BOT_USERNAME` — cosmetic / future use.

## Testing patterns

- Service unit tests: `@ExtendWith(MockitoExtension::class)` + `mockito-kotlin`. See `BacklogTaskServiceTest`, `UserAuthServiceTest`.
- Controller slice tests: `@WebMvcTest(SomeController::class)` + `@Import(SecurityConfiguration::class)`. Authenticate with `SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))))`, and add `.with(csrf())` for non-GET requests. See `BacklogTaskControllerTest`, `AuthControllerTest`.
- Integration tests: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureTestRestTemplate` + `@ActiveProfiles("dev"|"prod")`. See `SecurityIntegrationTest` — covers cookie reuse, CSRF enforcement, and prod-profile gating of dev-login.
- Time-dependent code (`TelegramAuthService`, `UserAuthService`) takes a `Clock` — tests inject `Clock.fixed(...)`.
