# Library extraction — design plan

## Why

Two pieces of this codebase are worth reusing across future projects:

1. **Envelope encryption** — versioned AES-256-GCM plus per-entity DEK-under-KEK envelope
   encryption with AAD binding.
2. **A minimal OpenRouter client** — request/response DTOs, a retrying transport, model-capability
   fetching, reasoning-effort resolution, and a small function-tool abstraction.

Both will become standalone, permissively-licensed libraries published via **JitPack**, and both
are consumed only by our own projects — so the goal is *reuse of exactly what we already need*, **not**
general-purpose flexibility. The actual library repos will be created in a **separate session**;
this repo has already been refactored so the extractable classes carry **no app-specific
dependencies** and the app talks to them through stable seams. Switching to the published
dependency later is then a mechanical delete-class → add-dependency → fix-imports step.

Packaging decision: **two separate repos** (one per library) — independent versioning and clean
isolation, at the cost of two small CI/release setups. (A single multi-module repo was the
alternative; rejected to keep the two unrelated domains fully decoupled.)

---

## Library 1 — `envelope-crypto`

### Extractable as-is (no app dependency)
- `crypto/AesGcmCipher.kt` — pure JDK AES-256-GCM with the versioned `[version|nonce|ct+tag]`
  envelope. Zero dependencies. Carries the nonce-per-key bound note (see below).
- `crypto/EnvelopeCipher.kt` — per-entity DEK-under-KEK envelope encryption with in-memory DEK
  cache and UUID→AAD binding. Depends only on `AesGcmCipher` and the `DekStore` seam.
- `crypto/DekStore.kt` — the persistence seam the consumer implements.

### Consumer-implemented seam
- **`DekStore`** — `exists(id)` / `findWrappedDek(id)` / `saveWrappedDek(id, wrapped)`. The app's
  `UserDekStore` / `BoardDekStore` implement it over JPA and own the persistence-only columns
  (`kek_version`, `created_at`). The KEK is supplied to `EnvelopeCipher` via a `() -> ByteArray`
  provider (resolved lazily on first use), so the library never binds to a config framework.

### Stays in the app (not extracted)
- `UserCryptoService` / `BoardCryptoService` — thin Spring `@Service` delegators over
  `EnvelopeCipher`; they own the transaction boundary and user/board naming.
- `UserDekStore` / `BoardDekStore`, the JPA entities/repositories, `DataEncryptionProperties`
  (Spring config; base64-decodes and validates the 32-byte KEK).

### Public surface (what a new consumer touches)
`EnvelopeCipher(kekProvider, dekStore, systemAad)` → `ensureKey` / `encrypt` / `decrypt` /
`encryptSystem` / `decryptSystem`; implement `DekStore`; reuse `AesGcmCipher` directly if only the
raw primitive is needed.

### Carry into the README
The **random-nonce bound**: safe to ~2³² encryptions under a single key (GCM birthday bound); a
high-volume single-key caller must rotate keys or use a nonce-reuse-resistant mode. Our per-tenant
DEK design keeps every key far below this.

---

## Library 2 — `openrouter-client`

### Extractable as-is (no app dependency)
- `ai/client/AiDtos.kt` — request/response DTOs, the string↔parts `MessageContent` union,
  prompt-caching `cache_control`, reasoning config, usage/token details (Jackson only).
- `ai/client/AiClient.kt` — RestClient transport, bearer auth, 3-attempt backoff on 5xx/429,
  central reasoning application. Depends only on the seams below.
- `ai/client/ModelCapabilityService.kt` — fetches `/model/{slug}` capabilities; startup prefetch +
  in-memory cache.
- `ai/client/ReasoningResolver.kt` — validates a configured effort against a model's advertised
  capabilities.
- `ai/client/AiClientProperties.kt`, `AiCallContext.kt` — config contract + usage-accounting carrier.
- `ai/tool/*` — `AiTool` / `ToolKind` / `ToolRegistry` function-tool abstraction.
- The pure `extractJsonObjectSpan` (`ai/AssistantJson.kt`) and `redactLlmResponse`
  (`util/LlmResponseRedaction.kt`) — LLM-output helpers.

### Consumer-implemented seams
- **`AiCallGate`** — pre-call veto (already a `fun interface`). App impl backs it with
  `AiAccessService` (opt-out + tier budget).
- **`AiCallListener`** — post-call `recordSuccess` / `recordFailure` for usage/metrics. App's
  `AiUsageTracker` (JPA + Micrometer) implements it.
- **`ReasoningEffortSource`** — `effortFor(conversationType)`. App's
  `AiPropertiesReasoningEffortSource` maps it from per-functionality config.
- **`AiClientProperties`** — `apiKey` / `baseUrl` / `configuredModels`, supplied by the app
  (`AiClientConfiguration` maps `AiProperties` onto it).

### Stays in the app (not extracted)
- `AiConversationManager` (conversation persistence via `ConversationService`), the metered
  `parseAssistantJsonResponseOrNull`, `AiUsageTracker`, the `AiCallGate` default bean,
  `AiPropertiesReasoningEffortSource`, `AiConversationType` (app-domain tags — the library treats
  `conversationType` as an opaque string), `AiProperties`.

### Spring stance
Keep the client **Spring-native** (RestClient, component beans) — matches our stack and avoids a
plain-JVM rewrite. Consumers are expected to be Spring Boot apps.

### Public surface
`AiClient.chat(request, context)`; implement `AiCallGate` / `AiCallListener` /
`ReasoningEffortSource` and provide `AiClientProperties`; register `AiTool`s in a `ToolRegistry`.

---

## Publishing (JitPack)

- Push each library to its own public GitHub repo, tag a release; JitPack builds on demand.
- Consumers add the `jitpack.io` Maven repo and `com.github.<user>.<repo>:<tag>`.
- **JVM 25 toolchain**: JitPack must build against JDK 25, and consumers pin to 25+. Fine for our
  own projects (same stack); documented so it isn't a surprise.
- **License**: permissive — MIT or Apache-2.0 (this repo has no `LICENSE` file yet; pick one per
  library at extraction time).
- Minimal polish deliberately: no javadoc jar / Maven Central / signing ceremony — JitPack from a
  tag is enough for internal reuse.

## The switch (future PR, after each library is published)

For each extracted class: delete the in-app copy, add the JitPack dependency, and fix imports
(package changes from `dev.itayp.tasker.*` to the library's package). Because this PR already made
the extractable classes dependency-clean and kept every app-facing API stable, the switch is
mechanical and low-risk — no behavior change, no consumer edits beyond imports.
