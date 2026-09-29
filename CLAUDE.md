# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**Backlog.fyi** is an AI-powered weekly planner: a task backlog plus a Telegram-driven weekly planning conversation that turns the tasks you agree to into calendar time blocks (today by emailing calendar invitations; reading Google Calendar directly is planned, see below). The internal codebase package is `dev.itayp.tasker` (historical name); the public-facing product name is **Backlog.fyi** — use that name in any user-visible copy (emails, UI strings, etc.). The product vision (core loop, task fields, integrations, non-goals) lives in `docs/SPEC.md` — read it before making design decisions. 

Current state: the backlog CRUD (tasks, categories, tags) is implemented end-to-end. Auth is **decoupled from Telegram** — registration/login go through an `auth_identities` table (one user → many providers), with Telegram Login Widget, passwordless email magic-link, demo, and dev-login session auth wired up, plus account-linking ("connected accounts"). The weekly planning conversation and LLM-driven planner are working, with email-invitation integration. No Google Calendar integration and no Google OAuth login yet (both planned). See `docs/archive/AUTH-DECOUPLING.md` for the auth-decoupling design and what's deferred (remaining deferred work is tracked as GitHub issues).

This is a non-commercial solo side project. It is currently running in production, but only serves a handful of beta users.

The source is public under **AGPL-3.0-only** (`LICENSE`). External contributions are deliberately not accepted (`CONTRIBUTING.md`) — the maintainer keeps full control and ownership, so there is no CLA and none is needed. Keep that in mind for anything public-facing: no secrets, no personal paths or hostnames, and nothing in the repo that only makes sense on the maintainer's machine.

## Working on the project

A few things to consider while working on the project:
- As a solo project, we have full responsibility and full knowledge - do not ignore pre-existing issues. If you notice an issue that might be a bug, surface it in your response.
- The number of active users is still very low, and they are all aware of the beta status. When absolutely necessary, breaking changes are not out of the question.
- On production, the app runs as a single instance on an Ubuntu VPS. Short downtime is acceptable.
- **The backlog lives in GitHub issues**, not in a markdown file. Labels: a type (`enhancement`, `tech-debt`, `security`, `epic`), one or more `area/*`, and `needs-discussion` for ideas that need a design conversation before anyone builds them. When a doc says something is deferred, link an issue rather than starting a new ideas/TODO file (`docs/IDEAS.md` was retired on purpose). Don't file security weaknesses as public issues.
- Deployment: **a push to `main` deploys.** The `deploy` job in `.github/workflows/gradle.yml` hands the built JAR to a reusable workflow in the separate ops repo (`Itaypk/itayp-dev`, Ansible), which is also where Nginx is configured. Don't merge or push to `main` unless asked to, and never deploy by other means.
- When something stands out, consider the product perspective: flag cases where added complexity may not be justified or where user value is unclear—suggesting alternatives where it makes sense.

## Repo layout

- `src/` — Kotlin/Spring Boot backend (package `dev.itayp.tasker`). Entry point: `src/main/kotlin/dev/itayp/tasker/TaskBoardApplication.kt`.
- `tasker-frontend/` — React + TypeScript + Vite app. **Bundled into the backend** at build time: the Gradle `buildFrontend` task runs `npm run build`, and `processResources` copies `tasker-frontend/dist/` into `src/main/resources/static/`. At runtime everything is served same-origin.
- `compose.yaml` — Postgres service for local dev. `spring-boot-docker-compose` starts it automatically on `bootRun`.
- `docs/SPEC.md` — product spec (source of truth for intent).
- `docs/MULTIMODAL-CAPTURE.md` — how Telegram quick-add captures from photos and voice notes.
- `tools/` — ad-hoc asset-prep scripts (background removal, bottom-gap leveling, WebP conversion). See `tools/README.md` for the "add a new board mascot" workflow.
- `.github/workflows/gradle.yml` — PR verification (triggers on PRs to `main`): a `frontend` job (`npm ci` + `lint` + `test` + `build` in `tasker-frontend/`), the backend `build` job (`./gradlew build` — compiles, runs backend tests, bundles the frontend), and a `dependency-submission` job for Dependabot. Keep both the frontend job and the backend job green — neither subsumes the other (Gradle's `buildFrontend` task runs `npm run build` as a side effect, but never `lint` or `test`).

## Web environment note

When running via **claude.ai/code** (the web environment), the sandbox does not have the project's JVM 25 toolchain installed. Do not attempt to compile, run tests, or start the server — those commands will fail. Instead, write the code, commit, and push; then wait for CI results to confirm correctness.

## Common commands

Backend (run from repo root):
- `./gradlew bootRun` — run the Spring Boot app with `--spring.profiles.active=dev` (set in the Gradle task); auto-starts Postgres via compose and bundles the frontend as a side effect of `processResources`.

Frontend (run from `tasker-frontend/`, only needed for fast iteration with HMR):
- `npm run dev` — Vite dev server on `:5173`. You'll need a reverse proxy or CORS for it to talk to the backend on `:8080`; in most workflows it's simpler to just `./gradlew bootRun` and edit through the bundled build.
- `npm run lint` — ESLint. Zero warnings/errors is the baseline CI gate — fix lint issues rather than disabling rules wholesale (an `eslint-disable` for a specific, justified line is fine; see `WeeklyPlanDrawer.tsx`'s `set-state-in-effect` disable for the convention).

## Auth model (important — affects every new endpoint)

There are four independent `SecurityFilterChain` beans:

1. **`h2ConsoleFilterChain` (`@Order(0)`)** - applies for the `dev` profile only; allows full access to the `/h2-console` endpoint.
2. **`prometheusFilterChain` (`@Order(1)`)** — matches only `/actuator/prometheus`. Stateless HTTP Basic Auth; credentials come from `PrometheusAuthProperties` (`TASKER_PROMETHEUS_USERNAME` / `TASKER_PROMETHEUS_PASSWORD`). CSRF disabled.
3. **`externalApiFilterChain` (`@Order(2)`)** — matches `/api/external/**`. Stateless bearer-token auth for the external API (see below). CSRF disabled.
4. **`securityFilterChain` (`@Order(3)`)** — everything else. Session-based with a `SameSite=Lax`, `HttpOnly`, `Secure` (prod) cookie (`SESSION`), 30-day rolling timeout. 

Session chain details:
- **CSRF** via `CookieCsrfTokenRepository.withHttpOnlyFalse()` — mutating requests must echo the `XSRF-TOKEN` cookie value as the `X-XSRF-TOKEN` header. Login endpoints (`/api/auth/dev-login`, `/api/auth/demo-login`, `/api/auth/email`) are exempt because they create the session; the magic-link callback and the Telegram OIDC start/callback are GETs (inherently CSRF-safe — the OAuth `state` parameter is the anti-forgery token). Account-linking endpoints (`/api/auth/identities/**`) are **not** exempt — the user is already signed in there. Frontend `api.ts` handles the token automatically.
- **Identity model**: login/registration is decoupled from Telegram. Each external login is a row in `auth_identities` (`provider` + `provider_user_id` → `user_id`; a user may have several). `UserAuthService.loginOrRegister(provider, providerUserId, verified, onExisting, onCreate)` is the generic core; the Telegram/email callers are thin wrappers. `TaskerPrincipal` carries only `userId`, so the session layer is provider-agnostic. **Never assume `user.telegramId` is non-null** outside the Telegram front door. Nor that a non-null one is reachable: a bot can't write to anyone who hasn't written to it first, so unprompted pushes gate on `users.telegram_chat_ready_at` (owned by `TelegramReachabilityService`, enforced by `ScheduledConversationChannelResolver`).
- **Login paths**:
  1. Telegram OIDC redirect flow (`TelegramOidcController`): `GET /api/auth/telegram/start` builds an OAuth2 Authorization Code + PKCE request (state/PKCE stashed in session) and 302s to `oauth.telegram.org`; `GET /api/auth/telegram/callback` exchanges the code for an `id_token` (JWT), validates it via `TelegramOidcService` (`NimbusJwtDecoder` against Telegram's JWKS — signature, issuer, `aud`=Client ID, expiry), then `loginOrRegister('telegram', …)`. The id_token's `id` claim is the Telegram user id, so it maps onto the same `auth_identities.provider_user_id` the legacy Login Widget used (no data migration). Requires `TASKER_TELEGRAM_CLIENT_ID` / `TASKER_TELEGRAM_CLIENT_SECRET` from BotFather → Bot Settings → Web Login.
  2. `POST /api/auth/email` (send) + `GET /api/auth/email/callback?token=…` (consume) — passwordless magic link. Single-use, 30-min, rate-limited token in `email_login_token` (pending email encrypted under the app KEK via `UserCryptoService.encryptSystem`; no user DEK exists yet). Always 200 on send (no account enumeration). Gated by `TASKER_EMAIL_ENABLED`.
  3. `POST /api/auth/demo-login` — ephemeral, deliberately channel-less demo user (no auth identity), 24-h TTL.
  4. `POST /api/auth/dev-login` — `@Profile("dev")` only, deterministic UUID (`UUID.nameUUIDFromBytes("tasker-dev-user".toByteArray())`). Used by `DevDataInitializer` on startup so H2 always has a usable dev user.
- **Account linking**: `GET /api/auth/identities` (`AccountLinkController`, authenticated) lists connected methods and `DELETE /api/auth/identities/{provider}` unlinks (guarded against removing the last login method). Linking a Telegram account uses the same OIDC round-trip as login: `GET /api/auth/telegram/link/start` (authenticated; `TelegramOidcController`) → callback in "link" mode → `AccountLinkService.linkTelegram` (re-publishes `UserPlanningScheduleChangedEvent` so a previously-skipped planning cron registers). Verifying an email in settings (`EmailVerificationService.confirmVerification`) also attaches an `email` identity, so a verified email is a login method. Linking an identity already owned by **another** account is refused (the callback redirects to `/settings?telegramLink=conflict`) — no data-bearing account merge.
- **Controllers inject `@AuthenticationPrincipal principal: TaskerPrincipal`** and use `principal.userId: UUID`. Never hardcode user ids.
- **Board-scoped content API**: tasks, categories, and tags are owned by a *board* (see `docs/BOARD-MODEL.md`), and their endpoints live under `/api/v1/boards/{boardId}/…`. The services (`BacklogTaskService` etc.) open every board-scoped method with `BoardMembershipService.requireMember(userId, boardId)`; a non-member gets a uniform 403 (`BoardAccessDeniedException`) whether or not the board exists. New board-content endpoints must follow this shape. The planner already spans every board a user belongs to (`PlannerTaskSelector`, `BacklogTaskService.getTasksAcrossBoards`/`findTask`); code paths that don't name a board explicitly (Telegram quick-add, dev planning, tag/category listing) fall back to `BoardMembershipService.resolveDefaultBoard` (the user's oldest membership) — this is intended default-board behavior, not a temporary bridge. Personal resources (settings, plans, planning, account, stats) stay user-scoped.
- **Writing a new session**: call `SessionAuthenticator.authenticate(principal, request, response)`. It saves the context via `HttpSessionSecurityContextRepository.saveContext` — **this call is mandatory** in Spring Security 7 or the session cookie won't be issued.
- **401 vs 403**: `exceptionHandling { authenticationEntryPoint = HttpStatusEntryPoint(UNAUTHORIZED) }` means unauth requests to `/api/**` return 401 JSON (the SPA listens for 401 and clears auth state). Missing/invalid CSRF returns 403.
- **Session persistence**: sessions are stored in the `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` tables via `spring-session-jdbc`, so they survive application restarts. `spring.session.jdbc.initialize-schema: never` — the schema is owned by Liquibase (the DDL lives at the end of changeset `001-schema.xml`). Every request updates `last_access_time`, so the 30-day TTL rolls forward for active users and only idle sessions expire. Expired rows are GC'd by Spring Session's internal scheduled cleanup.
- **Absolute session lifetime**: on top of the 30-day *idle* timeout, `AbsoluteSessionLifetimeFilter` caps a session's total life at `tasker.session.max-lifetime` (default **365 days**, `TASKER_SESSION_MAX_LIFETIME`). The anchor is a session attribute stamped by `SessionAuthenticator` on every login, so it means "a year since last sign-in". The filter is registered at order `-101` — *ahead* of the Spring Security chain — so an over-age session is invalidated before authorization runs, and it expires the `SESSION` / `XSRF-TOKEN` cookies on the way out.
- **Active sessions / revocation**: `SessionAuthenticator` also stamps the Spring Session principal-name index (`SPRING_SESSION.PRINCIPAL_NAME` = user UUID), a coarse device label, and the client IP **encrypted under the user's DEK**. `UserSessionService` reads them back via `FindByIndexNameSessionRepository.findByPrincipalName` to power `GET /api/auth/sessions` and `POST /api/auth/sessions/revoke-others` (`SessionController`, authenticated + CSRF), surfaced in Settings → General. No session identifier is ever sent to the browser — revocation is all-others-at-once, deliberately. Sessions created before this shipped carry none of these attributes and must keep listing with nulls.
- **CORS**: allow access from common localhost ports (NPM default and IntelliJ local files), and the base production URL (read from configuration)

## External API (`/api/external/v1`)

A token-authenticated task API for scripts, automations, and AI assistants. Full design note:
`docs/EXTERNAL-API.md`. Contract and skill are served publicly from `/external-api/openapi.yaml`
and `/external-api/SKILL.md` (that path prefix, not `/api/...`, so the `/api/**` authenticated
rule doesn't hide them).

- **Four places advertise the API; they move together.** `/.well-known/api-catalog` (RFC 9727
  linkset, generated by `ApiCatalogController` from `AppProperties.baseUrl` — the media type and
  absolute URLs are why it isn't static), `/llms.txt`, `sitemap.xml`, and the `service-desc` /
  `service-doc` link relations in `tasker-frontend/index.html`. Changing an endpoint path,
  a filename, or the base URL means updating all four. `DiscoveryIntegrationTest` pins them, and
  asserts response **bodies**: `SpaErrorController` forwards unmatched paths to `index.html`, so a
  broken path answers with a page rather than an error.

- **Do not add token auth to `/api/v1`, and do not point an agent at it.** `PUT /api/v1/boards/{b}/tasks/{id}`
  is a **full replace** — omitted fields reset to defaults, so a partial write silently destroys
  description, tags, deadline and priority. The external API exists partly to give unattended
  callers a real `PATCH`. Keeping the surfaces separate also bounds a leaked token's blast
  radius to task content (no account deletion, settings, planning, or board admin).
- **`ApiTokenAuthenticationFilter` installs the same `TaskerPrincipal` as the session flows.**
  This is load-bearing: below the security layer a token request is indistinguishable from a
  session one, so `BoardMembershipService.requireMember` and the per-user rate limiter work
  unchanged. Only the authorities differ (`EXTERNAL_READ` / `EXTERNAL_WRITE`, from the token's
  scope column, enforced on the filter chain rather than in controllers).
- **Isolation, both pinned by `ExternalApiSecurityIntegrationTest`**: a `SESSION` cookie cannot
  authenticate `/api/external/**` (`STATELESS` ⇒ `RequestAttributeSecurityContextRepository`,
  which never reads the session), and a token cannot authenticate `/api/v1/**` (the filter is on
  that one chain only).
- **Token minting stays on the session chain** (`/api/v1/api-tokens`, `ApiTokenController`) so a
  token can never mint a successor or widen its own scope. Only the SHA-256 hex digest is stored;
  the plaintext is shown once. Generate and hash every capability secret (magic
  links, invitations, email verification, API tokens) with `CapabilityTokens`; never store the plaintext.
- **New endpoints go in `dev.itayp.tasker.external` as thin adapters** — DTO shaping and argument
  validation only, delegating to the existing services. Never reimplement access checks there.
  Errors are RFC 7807 with a `detail` written for a model to read (name the allowed values), so
  an agent can self-correct.
- Remember to clear new user-owned tables in `AccountService.deleteUserData` (`api_token` already is).

## Security response headers and caching

All HTTP response security headers (CSP, HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy) **and all `Cache-Control` policy** are owned by **Nginx in front of the app**, not Spring. The Nginx config lives in a separate Ansible repo, `Itaypk/itayp-dev`, under `ansible/roles/nginx/` — there is **no** copy of it in this repo (one used to be checked in under `nginx/` and had drifted badly; don't reintroduce it).

- **Why not the app**: in prod the SPA's HTML document (`/`, and the SPA routes `/settings`, `/terms`, … which are `forward:/index.html`) is served by Spring's welcome-page / static-resource handler, and Spring Security's `HeaderWriterFilter` does **not** run on that forwarded response — so an app-layer CSP would silently miss the one response that matters most for XSS. Putting headers in Nginx covers it uniformly, and avoids emitting a duplicate header behind the proxy.
- **Do not add `contentSecurityPolicy`/header writers to `SecurityConfiguration`'s session chain.** `SecurityIntegrationTest` asserts the app emits *no* CSP precisely to catch a re-introduction (which would double the header behind Nginx). The dev-only `h2ConsoleFilterChain` is the lone exception (its own `frame-ancestors 'self'`).
- **CSP is per-vhost, never in the shared snippet.** `roles/nginx/files/snippets/security-headers.conf` (HSTS/X-Frame-Options/nosniff/Referrer/Permissions) is included by *every* vhost, so a CSP there would impose Backlog's policy on the other sites. CSP is instead a `csp:` field on the `tasks` service in `group_vars/all/main.yml`, emitted by `templates/vhost.conf.j2` into each Backlog `location` (the SPA static locations **and** the catch-all that serves the document forwards + API). Because Nginx `add_header` is replace-not-merge, every location that emits CSP must also re-`include security-headers.conf` or it drops the rest.
- **Local dev has no Nginx**, so responses carry no security headers there — expected; don't "fix" it by adding them to Spring.
- Known cosmetic wart: Spring's default `X-Frame-Options: DENY` still leaks on directly-served responses (e.g. `/assets/*`), doubling Nginx's `SAMEORIGIN`. Harmless (CSP `frame-ancestors 'none'` is authoritative), left as-is.

Caching, same ownership, same file (`templates/vhost.conf.j2`, the `spa: true` branch — the `tasks` service sets `spa: true`):

- `SecurityConfiguration` sets `cacheControl { disable() }` on the session and external-API chains, so **the app emits no `Cache-Control` of its own**. It is not that Spring sets a permissive value — the writer is off. Don't read that `disable()` as "assets are cached by Spring"; there is no `addResourceHandlers` override and no `spring.web.resources.cache` config anywhere.
- Nginx sets the policy on three groups of locations, each of which does `proxy_hide_header Cache-Control` first (so the app's value, if it ever had one, is replaced): `/assets/` → `public, max-age=31536000, immutable` (Vite content-hashes those filenames); `/` and `/index.html` → `no-cache, no-store, must-revalidate` (entry points are unhashed and must always re-resolve to current asset names); root-level unhashed assets — a regex over `svg|png|ico|jpg|jpeg|gif|webp|avif|webmanifest` anchored to one path segment, plus an exact `/manifest.json` — → `public, max-age=86400`. That last group is what covers the files Vite copies verbatim from `tasker-frontend/public/`: `manifest.json`, `apple-touch-icon.png`, `favicon.ico` and the PNG favicons (all referenced from `index.html`'s `<head>`), plus `og-image.png`.
- **Everything else gets no `Cache-Control` at all** — it falls through Nginx's catch-all `location /`, which doesn't touch the header. That covers `/api/**` (so task JSON ships with no `private` and no `no-store`), the discovery documents (`/.well-known/api-catalog`, `/llms.txt`), and `robots.txt` / `sitemap.xml`. Those are left to browser heuristic caching. For `/api/**` this is an accident of the blanket `disable()`, not a decision; `docs/FAST-INITIAL-LOAD.md` (option C) is where it gets revisited.
- The anchoring on that regex is load-bearing. A bare `\.(png|svg)$` would also match `/assets/*`, and **regex locations take precedence over the `location /assets/` prefix**, so the looser form would silently replace `immutable, max-age=31536000` with a day on every hashed asset. Don't relax it.
- **Local dev has no Nginx**, so nothing carries `Cache-Control` there either — same reason security headers are absent locally. Don't "fix" it in Spring.

## Data model

- **UUIDs everywhere**: user ids, task ids, category/tag ids. Stored as `UUID` columns with foreign keys to `users(id)`. We are using UUIDv7 whenever possible, for better DB index performance.
- **Auth tables**: `auth_identities` (changeset `005`) holds login identities — `(provider, provider_user_id)` unique, FK to `users(id)`. Profile/display data (Telegram username, encrypted email) stays on `users`; only the lookup *keys* live in `auth_identities`. `email_login_token` (changeset `006`) holds pending magic-link logins (email encrypted under the app KEK, `email_hash` as the non-secret handle). When deleting a user, clear `auth_identities` (and `email_login_token` is self-expiring) — see `AccountService.deleteUserData`. The legacy `users.telegram_id` / `users.email_hash` columns are still the source of profile state and are kept in sync, but identity *resolution* goes through `auth_identities`; retiring those columns is deferred (see `docs/archive/AUTH-DECOUPLING.md`).
- Liquibase runs on startup against H2 (dev/test) and Postgres (prod). `spring.jpa.hibernate.ddl-auto: validate` — Hibernate does **not** manage schema. The master changelog is `src/main/resources/db/changelog/db.changelog-master.xml`; additional changesets live under `src/main/resources/db/changelog/changesets/` and are wired in via `<include>`.
- **The app is deployed against a real Postgres database, so migrations are additive only.** Never edit a previously-applied changeset (including `id="1"`) — add a new changeset with the next integer id instead. Renames, column-type changes, and drops must be done through new changesets that preserve existing data.
- **Large text columns**: use `type="LONGVARCHAR"` in Liquibase and `@Column(columnDefinition = "TEXT")` in the JPA entity. Liquibase maps `LONGVARCHAR` → `TEXT` in Postgres and `VARCHAR` in H2; both satisfy Hibernate's schema validation for a `String` field. Do **not** use `type="TEXT"` in Liquibase — H2 maps that to CLOB, which fails validation. `context_block` (changeset 1) is a legacy exception: it's `VARCHAR(4096)` and has no `columnDefinition`.

## Stack notes that affect how you write code

- **JVM 25** via Gradle toolchain (`build.gradle.kts`). Never change Java version without explicit approval.
- **Kotlin Spring plugins**: `kotlin-spring` (auto-opens Spring-managed classes) and `kotlin-jpa` with `allOpen` for `@Entity`, `@MappedSuperclass`, `@Embeddable`. Don't mark JPA entities `open` manually.
- **Jackson**: uses `tools.jackson.module:jackson-module-kotlin` (Jackson 3.x, `tools.jackson` package), not `com.fasterxml.jackson.*`. Import accordingly.
- **Handlebars**: uses Handlebars.java for AI prompts and email templates. Prompt templates live in `src/main/resources/prompts/`, emails under `src/main/resources/emails`. 
- **Spring Boot 4.1.x**.
- **LLM**: We use a thin, hand-rolled abstraction over the OpenRouter API. We are not using frameworks such as Spring AI.
- **Databases**: H2 in-memory for dev (`application-dev.yaml`) and unit/slice tests. Postgres for prod (`application-prod.yaml`). Integration tests under `@ActiveProfiles("prod")` spin up a real Postgres instance via TestContainers (`AbstractIntegrationTest.Initializer`). Config is split across `application.yaml` (base), `application-dev.yaml`, and `application-prod.yaml`.
- **Compiler flags**: `-Xjsr305=strict` (JSR-305 nullability → errors) and `-Xannotation-default-target=param-property` (Kotlin 2.x annotation target default). Keep nullability annotations honest.

## Frontend specifics

- React 19, Vite 8, TypeScript ~6.0. Routing via `react-router-dom` v7 (`BrowserRouter` in `main.tsx`); the SPA fallback for non-root client routes (e.g. `/terms`, `/privacy`) lives in `controller/SpaForwardController.kt` — add new top-level routes there too.
- SPA conventions (anonymous first-paint budget, self-hosted web fonts, co-located CSS Modules, static content pages, the auth-aware shell, `api.ts`) live in `tasker-frontend/CLAUDE.md`, which loads automatically when working under that directory.

## Configuration

Full environment-variable reference — names, defaults, provenance — is in `docs/CONFIGURATION.md`. The parts that bite if you don't know them:

- **`TASKER_DATA_KEK` is required in prod, and losing it permanently loses all encrypted data.** Keep the prod value offline and out of any archive that also holds DB dumps.
- Multimodal quick-add is **declined** (with a "describe it in text" reply) unless the resolved `TASKER_AI_MULTIMODAL_MODEL` advertises the matching `input_modalities` in OpenRouter's model capabilities — so leaving it unset keeps the feature dark. Attachment bytes are never persisted or logged.
- The AI tier cap bounds **claimed** accounts holding the full `standard`/`unlimited` grant. Over the cap, new registrations fall back to the bounded `AiTier.DEMO` tier (100k tokens/30d, vs. `standard`'s 1M) rather than losing AI access outright, so there is no runaway-bill risk in granting it unconditionally. Unclaimed/demo accounts always start on `DEMO` and bypass the cap entirely (a demo signup never proves it's a real user, and AI — including the web weekly-planning conversation, which needs no Telegram — is part of the funnel); `UserSettingsService.upgradeToStandardOnClaim` re-runs the cap check when such an account claims itself. `ai_tier = "none"` is no longer reachable from registration — it remains only as a manual admin lever (e.g. to suspend a single account); there is no self-serve "request access" flow. The count-then-insert check isn't transactionally atomic against concurrent registrations; at this app's beta scale, overshooting by one or two is an accepted risk.
- Email is split into **two independent senders with separate SMTP accounts** — `auth` (critical path: login / register / verification magic links) and `scheduling` (calendar invites) — so a deliverability problem on one mailbox can't take down the other. Delivery is tracked by the `tasker.email.sent{purpose,outcome}` Prometheus counter; hook it to a Grafana alert, especially `purpose=auth,outcome=failure`. The scheduling sender retries transient SMTP failures in-process (`RetryingOutboundChannel`, inside the metrics decorator), so `tasker.email.sent` counts one *final* outcome per email; recovered attempts show up on `tasker.email.retries{purpose}`. Failures before the send (slot parsing, template rendering) never reach the channel and are only logged.
- The application-wide email domain blocklist (`EmailDomainBlocklistService`) is checked on every new address — magic-link login, settings email change, and board invitations all throw `BlockedEmailDomainException` → 400 with `code: BLOCKED_EMAIL_DOMAIN`. Login rejects **loudly** (it used to no-op silently): whether a domain is disposable says nothing about whether an account exists, so this leaks nothing, and at ~75k domains a silent no-op would make a false positive indistinguishable from mail that never arrived. The per-address send rate limit stays silent — that one *is* account-adjacent.
- `VITE_*` values are **baked into the bundle at build time**, so changing one needs a rebuild — set it in the build environment, not at runtime.

## Internationalization

Use the user's selected language and locale in the various communication channels (Telegram, email). We are using Spring's `MessageSource`, with message bundles (on `src/main/resources`).
The web UI is localized with i18next; `LAUNCHED_UI_LANGUAGES` (`src/i18n/index.ts`) is `en`, `he`, `ru`, `ar` — `src/i18n/catalog.test.ts` enforces exact key parity with `en` for **every** launched language, so a new user-facing string needs a key in all four `tasker-frontend/src/locales/*/translation.json` files or CI fails. New CSS must use logical properties (`margin-inline`, …); `src/i18n/logical-css.test.ts` fails on physical directional declarations. Emails and Telegram communications are fully localized — keep it that way. See `docs/archive/I18N.md`. 
For gendered language (he, ar), always phrase messages in a gender-neutral way.

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
