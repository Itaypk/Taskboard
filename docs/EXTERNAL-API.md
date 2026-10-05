# External API

`/api/external/v1` is a token-authenticated task API for anything outside the web app —
scripts, automations, and AI assistants. It sits alongside the SPA's session-authenticated
`/api/v1` rather than replacing it.

Public entry points:

- `/external-api/openapi.yaml` — the contract (OpenAPI 3.1, hand-authored).
- `/external-api/SKILL.md` — a Claude skill teaching an agent to use it.

Both are static resources, deliberately served from `/external-api/` and not `/api/...`:
`authorize("/api/**", authenticated)` would otherwise put the docs behind auth.

## Why a separate surface

Two reasons, in order of importance.

**1. The SPA's task API is unsafe for an unattended caller.** `CreateBacklogTaskRequest` and
`UpdateBacklogTaskRequest` are field-identical, and `PUT /api/v1/boards/{b}/tasks/{id}` is a
full replace whose omitted fields fall back to defaults. An agent that PUTs
`{"title": "...", "status": "done"}` to mark a task done silently destroys its description,
tags, deadline and priority. That is fine for a form that always submits every field, and a
trap for anything else. The external API offers a genuine `PATCH`, plus the endpoints the SPA
never needed: get-by-id, complete, archive, and free-text search.

**2. Blast radius.** A token scoped to `/api/external/**` cannot reach account deletion,
settings, planning sessions, board administration, or token minting. Adding bearer auth to
`/api/v1` would have handed every token the full account.

## Token model

`api_token` (changeset 012). Only the SHA-256 hex digest of the secret is stored; the plaintext
is shown once at creation and is unrecoverable. Secrets are 32 bytes from `SecureRandom`,
base64url-encoded, prefixed `blf_`.

Unsalted SHA-256 is the right choice here, unlike for a password: the input is 256 bits of
uniform entropy, so there is no dictionary to run, and a fast digest is what makes an indexed
single-row lookup viable on the authentication hot path.

Scope is `read` or `write`, enforced as Spring authorities (`EXTERNAL_READ` / `EXTERNAL_WRITE`)
on the filter chain rather than in controller code. `last_used_at` is written at most once a
minute per token, guarded by an in-process map, so reads don't become writes.

Tokens can expire. Settings offers 30 days, 90 days (the default), a year, or no expiration;
the API takes `expiresInDays` (1–365, omitted means never). "No expiration" stays available on
purpose: an unattended automation that silently dies after 90 days is its own failure mode, and
the user is the one who knows which they are building. An expired token answers the same
uniform 401 as a revoked or unknown one — the skill tells an agent that a 401 means "ask the user
for a new token" — and stays listed in Settings, marked expired, so the user can see why an
automation stopped.

Tokens are capped at 5 live per user; expired tokens don't count. Minting lives at `POST /api/v1/api-tokens` — on the
**session** chain — so a leaked token can never mint a successor or widen its own scope.

## Auth wiring

`SecurityConfiguration.externalApiFilterChain` (`@Order(2)`) matches `/api/external/**`. It is
modelled on `prometheusFilterChain`, the other stateless chain: `SessionCreationPolicy.STATELESS`,
CSRF disabled, `HttpStatusEntryPoint(401)`.

Two isolation properties, both pinned by `ExternalApiSecurityIntegrationTest`:

- **A session cookie cannot authenticate here.** `STATELESS` makes the chain's context repository
  a `RequestAttributeSecurityContextRepository`, which never consults the HTTP session.
- **A token cannot authenticate `/api/v1/**`.** `ApiTokenAuthenticationFilter` is registered on
  this chain only.

`ApiTokenAuthenticationFilter` installs the **same `TaskerPrincipal`** the session flows use.
That is the load-bearing design decision: below the security layer a token request is
indistinguishable from a session one, so every `BoardMembershipService.requireMember` check and
the per-user rate limiter keep working with no change. Only the granted authorities differ.

## Adding an endpoint

Controllers under `dev.itayp.tasker.external` are thin adapters — argument validation and DTO
shaping only. All authorization and business rules stay in the existing services, each of which
opens with `requireMember`. Do not reimplement access checks here.

Error responses are RFC 7807. Write `detail` for a model to read: name what was wrong and what
the allowed values are, so an agent can self-correct without a human.

## Discoverability

The contract and the skill are served from `/external-api/` (that prefix, not `/api/...`, so the
`/api/**` authenticated rule doesn't hide them). Four places advertise them; if any endpoint,
filename or base URL changes, all four move together:

| Where | What it says | Source |
|---|---|---|
| `/.well-known/api-catalog` | RFC 9727 linkset: the API, plus RFC 8631 `service-desc` (openapi.yaml) and `service-doc` (SKILL.md) links | `controller/ApiCatalogController.kt` |
| `/llms.txt` | The llmstxt.org index — what this site is and where the API docs are | `static/llms.txt` |
| `/sitemap.xml` | Both files as crawlable URLs | `static/sitemap.xml` |
| `index.html` `<head>` | `service-desc` / `service-doc` link relations | `tasker-frontend/index.html` |

The catalogue is **generated**, unlike every other discovery file in the repo, because RFC 9727
requires `application/linkset+json` (an extensionless static file would be served as
`application/octet-stream`) and absolute URLs (a hardcoded `backlog.fyi` would be wrong in dev).
Both come out of `AppProperties.baseUrl`. A side benefit is that no dotted `.well-known/`
directory has to exist on disk.

`DiscoveryIntegrationTest` pins all of it, asserting response **bodies** rather than status codes:
`SpaErrorController` forwards unmatched paths to `index.html`, so a broken path would otherwise
answer with a page instead of an error.

Not added: `/.well-known/ai-plugin.json`. That was the ChatGPT plugin manifest, and plugins were
sunset in 2024.

## Deliberate omissions

- **No hard delete.** `POST /{id}/archive` is the reversible equivalent.
- **`hiddenFromAssistant` is respected by default.** That flag is the user's explicit
  "not for the AI" marker; `?includeHidden=true` opts back in.
- **No OpenAPI generation.** springdoc pulls swagger-core (Jackson 2) into a Jackson 3
  application, and its Spring Boot 4 line is new. The spec is hand-authored instead. Revisit if
  the surface outgrows ~20 endpoints.
- **No MCP server.** This REST surface is the substrate one would sit on; worth building only
  if a client appears that can't just read the skill.
