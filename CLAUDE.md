# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**Backlog.fyi** is an AI-powered weekly planner: a task backlog plus a Telegram-driven weekly planning conversation that reads Google Calendar and writes agreed tasks back as time blocks. The internal codebase package is `dev.itayp.tasker` (historical name); the public-facing product name is **Backlog.fyi** — use that name in any user-visible copy (emails, UI strings, etc.). The product vision (core loop, task fields, integrations, non-goals) lives in `docs/SPEC.md` — read it before making design decisions. 

Current state: the backlog CRUD (tasks, categories, tags) is implemented end-to-end. Auth is **decoupled from Telegram** — registration/login go through an `auth_identities` table (one user → many providers), with Telegram Login Widget, passwordless email magic-link, demo, and dev-login session auth wired up, plus account-linking ("connected accounts"). The weekly planning conversation and LLM-driven planner are working, with email-invitation integration. No Google Calendar integration and no Google OAuth login yet (both planned). See `docs/AUTH-DECOUPLING.md` for the auth-decoupling design and what's deferred.

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
  - `controller/` — REST controllers. `AuthController` handles `/me`; `TelegramOidcController` handles Telegram OIDC login/link redirects; `DevAuthController` is `@Profile("dev")` only.
  - `service/` — `TelegramOidcService` (Telegram OIDC redirect-flow verify), `UserAuthService` (login-or-register), per-resource services.
  - `security/` — `TaskerPrincipal` (UUID user id) + `SessionAuthenticator` (creates session on successful auth).
  - `jpa/`, `repository/`, `model/` — entities, Spring Data repositories, and API DTOs.
  - `resources/db/changelog/` — Liquibase changelog master + changesets.
- `tasker-frontend/` — React + TypeScript + Vite app. **Bundled into the backend** at build time: the Gradle `buildFrontend` task runs `npm run build`, and `processResources` copies `tasker-frontend/dist/` into `src/main/resources/static/`. At runtime everything is served same-origin.
- `compose.yaml` — Postgres service for local dev. `spring-boot-docker-compose` starts it automatically on `bootRun`.
- `docs/SPEC.md` — product spec (source of truth for intent).
- `tools/` — ad-hoc asset-prep scripts (background removal, bottom-gap leveling, WebP conversion). See `tools/README.md` for the "add a new board mascot" workflow.
- `.github/workflows/gradle.yml` — PR verification (triggers on PRs to `main`): a `frontend` job (`npm ci` + `lint` + `test` + `build` in `tasker-frontend/`), the backend `build` job (`./gradlew build` — compiles, runs backend tests, bundles the frontend), and a `dependency-submission` job for Dependabot. Keep both the frontend job and the backend job green — neither subsumes the other (Gradle's `buildFrontend` task runs `npm run build` as a side effect, but never `lint` or `test`).

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
- `npm run lint` — ESLint. Zero warnings/errors is the baseline CI gate — fix lint issues rather than disabling rules wholesale (an `eslint-disable` for a specific, justified line is fine; see `WeeklyPlanDrawer.tsx`'s `set-state-in-effect` disable for the convention).
- `npm run test` — Vitest (`vitest run`).

## Auth model (important — affects every new endpoint)

There are three independent `SecurityFilterChain` beans:

1. **`h2ConsoleFilterChain` (`@Order(0)`)** - applies for the `dev` profile only; allows full access to the `/h2-console` endpoint.
2. **`prometheusFilterChain` (`@Order(1)`)** — matches only `/actuator/prometheus`. Stateless HTTP Basic Auth; credentials come from `PrometheusAuthProperties` (`TASKER_PROMETHEUS_USERNAME` / `TASKER_PROMETHEUS_PASSWORD`). CSRF disabled.
3. **`securityFilterChain` (`@Order(2)`)** — everything else. Session-based with a `SameSite=Lax`, `HttpOnly`, `Secure` (prod) cookie (`SESSION`), 30-day rolling timeout. 

Session chain details:
- **CSRF** via `CookieCsrfTokenRepository.withHttpOnlyFalse()` — mutating requests must echo the `XSRF-TOKEN` cookie value as the `X-XSRF-TOKEN` header. Login endpoints (`/api/auth/dev-login`, `/api/auth/demo-login`, `/api/auth/email`) are exempt because they create the session; the magic-link callback and the Telegram OIDC start/callback are GETs (inherently CSRF-safe — the OAuth `state` parameter is the anti-forgery token). Account-linking endpoints (`/api/auth/identities/**`) are **not** exempt — the user is already signed in there. Frontend `api.ts` handles the token automatically.
- **Identity model**: login/registration is decoupled from Telegram. Each external login is a row in `auth_identities` (`provider` + `provider_user_id` → `user_id`; a user may have several). `UserAuthService.loginOrRegister(provider, providerUserId, verified, onExisting, onCreate)` is the generic core; the Telegram/email callers are thin wrappers. `TaskerPrincipal` carries only `userId`, so the session layer is provider-agnostic. **Never assume `user.telegramId` is non-null** outside the Telegram front door.
- **Login paths**:
  1. Telegram OIDC redirect flow (`TelegramOidcController`): `GET /api/auth/telegram/start` builds an OAuth2 Authorization Code + PKCE request (state/PKCE stashed in session) and 302s to `oauth.telegram.org`; `GET /api/auth/telegram/callback` exchanges the code for an `id_token` (JWT), validates it via `TelegramOidcService` (`NimbusJwtDecoder` against Telegram's JWKS — signature, issuer, `aud`=Client ID, expiry), then `loginOrRegister('telegram', …)`. The id_token's `id` claim is the Telegram user id, so it maps onto the same `auth_identities.provider_user_id` the legacy Login Widget used (no data migration). Requires `TASKER_TELEGRAM_CLIENT_ID` / `TASKER_TELEGRAM_CLIENT_SECRET` from BotFather → Bot Settings → Web Login.
  2. `POST /api/auth/email` (send) + `GET /api/auth/email/callback?token=…` (consume) — passwordless magic link. Single-use, 30-min, rate-limited token in `email_login_token` (pending email encrypted under the app KEK via `UserCryptoService.encryptSystem`; no user DEK exists yet). Always 200 on send (no account enumeration). Gated by `TASKER_EMAIL_ENABLED`.
  3. `POST /api/auth/demo-login` — ephemeral, deliberately channel-less demo user (no auth identity), 24-h TTL.
  4. `POST /api/auth/dev-login` — `@Profile("dev")` only, deterministic UUID (`UUID.nameUUIDFromBytes("tasker-dev-user".toByteArray())`). Used by `DevDataInitializer` on startup so H2 always has a usable dev user.
- **Account linking**: `GET /api/auth/identities` (`AccountLinkController`, authenticated) lists connected methods and `DELETE /api/auth/identities/{provider}` unlinks (guarded against removing the last login method). Linking a Telegram account uses the same OIDC round-trip as login: `GET /api/auth/telegram/link/start` (authenticated; `TelegramOidcController`) → callback in "link" mode → `AccountLinkService.linkTelegram` (re-publishes `UserPlanningScheduleChangedEvent` so a previously-skipped planning cron registers). Verifying an email in settings (`EmailVerificationService.confirmVerification`) also attaches an `email` identity, so a verified email is a login method. Linking an identity already owned by **another** account is refused (the callback redirects to `/settings?telegramLink=conflict`) — no data-bearing account merge.
- **Controllers inject `@AuthenticationPrincipal principal: TaskerPrincipal`** and use `principal.userId: UUID`. Never hardcode user ids.
- **Board-scoped content API**: tasks, categories, and tags are owned by a *board* (see `docs/BOARD-SHARING.md`), and their endpoints live under `/api/v1/boards/{boardId}/…`. The services (`BacklogTaskService` etc.) open every board-scoped method with `BoardMembershipService.requireMember(userId, boardId)`; a non-member gets a uniform 403 (`BoardAccessDeniedException`) whether or not the board exists. New board-content endpoints must follow this shape. Planner/Telegram code still reaches tasks through `userId`-only bridge methods that resolve the user's sole board — these are temporary until the planner is board-aware (`docs/BOARD-SHARING-PHASE1.md`). Personal resources (settings, plans, planning, account, stats) stay user-scoped.
- **Writing a new session**: call `SessionAuthenticator.authenticate(principal, request, response)`. It saves the context via `HttpSessionSecurityContextRepository.saveContext` — **this call is mandatory** in Spring Security 7 or the session cookie won't be issued.
- **401 vs 403**: `exceptionHandling { authenticationEntryPoint = HttpStatusEntryPoint(UNAUTHORIZED) }` means unauth requests to `/api/**` return 401 JSON (the SPA listens for 401 and clears auth state). Missing/invalid CSRF returns 403.
- **Session persistence**: sessions are stored in the `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` tables via `spring-session-jdbc`, so they survive application restarts. `spring.session.jdbc.initialize-schema: never` — the schema is owned by Liquibase (changeset `002-spring-session.xml`). Every request updates `last_access_time`, so the 30-day TTL rolls forward for active users and only idle sessions expire. Expired rows are GC'd by Spring Session's internal scheduled cleanup.
- **CORS**: allow access from common localhost ports (NPM default and IntelliJ local files), and the base production URL (read from configuration)

## Security response headers

All HTTP response security headers (CSP, HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy) are owned by **Nginx in front of the app**, not Spring. The Nginx config lives in a separate Ansible repo (`../itayp_dev`, role `nginx`).

- **Why not the app**: in prod the SPA's HTML document (`/`, and the SPA routes `/settings`, `/terms`, … which are `forward:/index.html`) is served by Spring's welcome-page / static-resource handler, and Spring Security's `HeaderWriterFilter` does **not** run on that forwarded response — so an app-layer CSP would silently miss the one response that matters most for XSS. Putting headers in Nginx covers it uniformly, and avoids emitting a duplicate header behind the proxy.
- **Do not add `contentSecurityPolicy`/header writers to `SecurityConfiguration`'s session chain.** `SecurityIntegrationTest` asserts the app emits *no* CSP precisely to catch a re-introduction (which would double the header behind Nginx). The dev-only `h2ConsoleFilterChain` is the lone exception (its own `frame-ancestors 'self'`).
- **CSP is per-vhost, never in the shared snippet.** `roles/nginx/files/snippets/security-headers.conf` (HSTS/X-Frame-Options/nosniff/Referrer/Permissions) is included by *every* vhost, so a CSP there would impose Backlog's policy on the other sites. CSP is instead a `csp:` field on the `tasks` service in `group_vars/all/main.yml`, emitted by `templates/vhost.conf.j2` into each Backlog `location` (the SPA static locations **and** the catch-all that serves the document forwards + API). Because Nginx `add_header` is replace-not-merge, every location that emits CSP must also re-`include security-headers.conf` or it drops the rest.
- **Local dev has no Nginx**, so responses carry no security headers there — expected; don't "fix" it by adding them to Spring.
- Known cosmetic wart: Spring's default `X-Frame-Options: DENY` still leaks on directly-served responses (e.g. `/assets/*`), doubling Nginx's `SAMEORIGIN`. Harmless (CSP `frame-ancestors 'none'` is authoritative), left as-is.

## Data model

- **UUIDs everywhere**: user ids, task ids, category/tag ids. Stored as `UUID` columns with foreign keys to `users(id)`.
- **Auth tables**: `auth_identities` (changeset `005`) holds login identities — `(provider, provider_user_id)` unique, FK to `users(id)`. Profile/display data (Telegram username, encrypted email) stays on `users`; only the lookup *keys* live in `auth_identities`. `email_login_token` (changeset `006`) holds pending magic-link logins (email encrypted under the app KEK, `email_hash` as the non-secret handle). When deleting a user, clear `auth_identities` (and `email_login_token` is self-expiring) — see `AccountService.deleteUserData`. The legacy `users.telegram_id` / `users.email_hash` columns are still the source of profile state and are kept in sync, but identity *resolution* goes through `auth_identities`; retiring those columns is deferred (see `docs/AUTH-DECOUPLING.md`).
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
- Auth-aware shell in `src/App.tsx`: `AuthProvider` (in `src/auth/AuthContext.tsx`) runs `GET /api/auth/me` on mount. `LoginPage` offers Telegram (an outlined button that navigates to the OIDC redirect flow at `/api/auth/telegram/start`), passwordless email (inline form → magic link), a sandbox/demo button, a Google button (stubbed "Soon"), and a dev-login button gated on `import.meta.env.DEV`; it surfaces `?telegramLogin=failed|unavailable` callback notices (auto-opening the modal). `SettingsModal` has a "Connected accounts" section (`ConnectedAccounts.tsx`) to link Telegram (navigates to `/api/auth/telegram/link/start`, surfaces `?telegramLink=…`) / unlink methods. The display name degrades gracefully for channel-less users (`UserMenu`: display name → telegram → email → "Account").
- `src/api.ts` wraps `fetch` with `credentials: 'include'`, echoes the XSRF cookie as `X-XSRF-TOKEN` on mutating requests, and dispatches an `auth:unauthenticated` event on 401 so `AuthContext` can flip to the login page.
- Frontend env vars: none required for auth — Telegram login is a backend-driven OAuth redirect, so the SPA just links to `/api/auth/telegram/start`. (`VITE_TELEGRAM_BOT_USERNAME` is no longer used now that the iframe widget is gone.)
  - `VITE_SUPPORT_EMAIL` — public support/contact address, **baked into the bundle at build time** by the Gradle `buildFrontend` task (`npm run build`). Read once in `src/config.ts` (defaults to `hello@backlog.fyi` when unset) and used for the import-error "Contact support" link and the ToS/Privacy contact lines (the policy markdown carries a `{{SUPPORT_EMAIL}}` placeholder that `PolicyPage` substitutes). Since it's build-time, changing it needs a rebuild — set it in the build environment, not at runtime.
- Backend env vars (read via `TelegramAuthProperties`):
  - `TASKER_TELEGRAM_CLIENT_ID` — OIDC client id (bot id) for login; also the expected `aud` of the id_token. From BotFather → Bot Settings → Web Login.
  - `TASKER_TELEGRAM_CLIENT_SECRET` — OIDC client secret (HTTP Basic credential for the token exchange). From the same BotFather screen.
  - `TASKER_TELEGRAM_BOT_TOKEN` — bot messaging token (planning conversation); not used by login anymore.
  - `TASKER_TELEGRAM_BOT_USERNAME` — cosmetic / future use.
- Backend env vars (read via `PrometheusAuthProperties`) — **required in prod, dev defaults apply otherwise**:
  - `TASKER_PROMETHEUS_USERNAME` — Basic Auth username for `/actuator/prometheus` (default: `prometheus`).
  - `TASKER_PROMETHEUS_PASSWORD` — Basic Auth password for `/actuator/prometheus` (default: `prometheus-dev`).
- Backend env vars for the production database (required when running with `prod` profile):
  - `TASKER_DB_URL` — JDBC URL, e.g. `jdbc:postgresql://host:5432/taskboard`.
  - `TASKER_DB_USERNAME` / `TASKER_DB_PASSWORD` — Postgres credentials.
- Backend env var for at-rest data encryption (required in prod):
  - `TASKER_DATA_KEK` — base64-encoded 32-byte key. Wraps per-user DEKs that encrypt task titles, descriptions, LLM messages, user settings, etc. **Losing this key permanently loses all encrypted data.** Generate with `openssl rand -base64 32`; store the prod value offline (e.g. password manager), and keep it out of any archive that also includes DB dumps. Dev/test fall back to a checked-in placeholder key — never reuse that for prod.
- Backend env vars for the email integration. Email is split into two independent senders, each
  with its own SMTP account, so a deliverability problem on one mailbox can't take down the other:
  **`auth`** (critical path — login / register / verification magic links) and **`scheduling`**
  (calendar invites). Delivery is tracked by the `tasker.email.sent{purpose,outcome}` Prometheus
  counter (hook it to a Grafana alert, especially `purpose=auth,outcome=failure`).
  - `TASKER_EMAIL_ENABLED` - toggle email integration (default: false). When false, both senders
    log instead of sending (and the magic link is printed to the log).
  - `TASKER_EMAIL_BLOCKED_DOMAINS` — comma-separated application-wide email domain blocklist
    (`EmailDomainBlocklistService`), checked on every new address: magic-link login (silently
    no-ops, same as the rate limit, to preserve the no-enumeration guarantee), settings email
    change, and board invitations (both throw `BlockedEmailDomainException` → 400). An entry
    matches that domain exactly; prefix with `*.` to match subdomains only, not the domain
    itself — list both forms to block a domain and all its subdomains.
  - Auth sender: `TASKER_EMAIL_AUTH_FROM`, `TASKER_EMAIL_AUTH_FROM_NAME` (default: Backlog.fyi),
    `TASKER_EMAIL_AUTH_SMTP_HOST` (default: smtp.protonmail.ch), `TASKER_EMAIL_AUTH_SMTP_PORT`
    (default: 587), `TASKER_EMAIL_AUTH_SMTP_USERNAME`, `TASKER_EMAIL_AUTH_SMTP_PASSWORD`.
  - Scheduling sender: `TASKER_EMAIL_SCHEDULING_FROM`, `TASKER_EMAIL_SCHEDULING_FROM_NAME`,
    `TASKER_EMAIL_SCHEDULING_SMTP_HOST`, `TASKER_EMAIL_SCHEDULING_SMTP_PORT`,
    `TASKER_EMAIL_SCHEDULING_SMTP_USERNAME`, `TASKER_EMAIL_SCHEDULING_SMTP_PASSWORD`.
- Backend env var for the in-app feedback form (`FeedbackController` → `FeedbackService`,
  `POST /api/v1/feedback`, authenticated + CSRF):
  - `TASKER_FEEDBACK_RECIPIENT` — mailbox that feedback submissions are forwarded to. Sent over
    the **auth** email sender (same SMTP credentials as login/verification mail). When blank,
    falls back to the auth sender's own `from` address. Per-user rate-limited
    (`tasker.rate-limit.feedback`); the admin-facing email is English-only (not user-localised).

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
- Time-dependent code (`UserAuthService`, `EmailLoginService`, …) takes a `Clock` — tests inject `Clock.fixed(...)`. (Telegram id_token freshness is enforced by the JWT `exp`/`nbf` validators, not a `Clock`.)
- **Frontend** (`tasker-frontend/`): Vitest + React Testing Library + `@testing-library/jest-dom`, jsdom environment — config is the `test` field in `vite.config.ts`, setup file `src/setupTests.ts` (imports `@testing-library/jest-dom/vitest` for the matcher types/assertions). Co-locate test files next to what they cover, named `Foo.test.tsx` (no `__tests__` directory convention) — see `src/NotFoundPage.test.tsx` for the baseline shape. Coverage is intentionally minimal today (CI just needs lint + build + test to stay green); grow it incrementally alongside new/changed frontend code rather than backfilling all at once.
