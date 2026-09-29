# Faster initial display of tasks

Exploration note on what a signed-in user waits for between opening the app and seeing their tasks,
and what could be done about it.

Status: **closed for now — measured, one fix shipped, direction parked.** See "Bottom line" at the
end for where this landed and why nothing further is recommended. In short: the single largest item
was a 300 ms React Suspense fallback throttle that no option below anticipated; removing it roughly
halved time-to-tasks. Everything else that was measured turned out to be either already minimal or
not worth its cost. **Options A–H and the recommendation ladder below are superseded** by the
measurement sections at the end — they are kept as the reasoning that led there, not as a build
plan, and several of their claims are corrected further down. Supersedes the earlier one-line
idea ("Smoother loading — show a cached copy in read-only mode…"), now tracked as issue #265.

None of options A–H has been built. Some of the *pre-existing issues* listed at the end have since
been fixed (the N+1, the stale Nginx copy, the misleading comments, and the uncached `public/` assets);
each is marked inline. The one that still blocks option C — no `Cache-Control` on `/api/**` — is open.

The trigger is felt slowness, specifically on **mobile cold open**, and the original idea's framing: it is
acceptable to show stale data before the initial load, and acceptable to be read-only until it lands.
That framing turns out to be more permissive than the problem requires — see "Why the cached-copy idea
is the wrong first move".

---

## Two constraints this note is written against

**1. The anonymous cold load must not regress.** `tasker-frontend/CLAUDE.md` records why the signed-in
surface is a `React.lazy` chunk at all: the landing page is what a cold visitor, every crawler and
every PageSpeed run downloads, and before the split it carried drag-and-drop, all the modals and the
markdown renderer — the mobile FCP/LCP showed it. The anonymous number is the commercially
load-bearing one. **No option here may cause an unauthenticated visitor to fetch the `Board` chunk.**
Every option below is marked *anonymous-safe* or *needs gating* accordingly.

**2. Task content at rest on the client is a cost, not a free lunch.** Titles, descriptions and notes
are user-authored and encrypted at rest server-side under the board DEK. Any option that writes them
to `localStorage`/IndexedDB moves plaintext onto the device, outside that protection, surviving logout
unless explicitly wiped.

## What actually happens today

Traced through `tasker-frontend/src/main.tsx`, `src/auth/AuthContext.tsx`, `src/App.tsx`, `src/Board.tsx`:

1. `index.html` (served `no-store`, correctly — it names the hashed bundles) → entry JS.
2. **Locale catalog.** `bootCatalogReady` (`src/i18n/index.ts:160`) gates `createRoot` in `main.tsx`.
   English is bundled and resolves in a microtask; `he`/`ru`/`ar` are a dynamic `import()` of a JSON
   chunk that blocks mounting entirely — *nothing at all renders* until it lands.
3. `GET /api/auth/me` (`AuthContext.tsx`), with the shell gated on it (`App.tsx:66`).
4. **The `Board` chunk.** `App.tsx:15,74` — the `import()` only *starts* once `/me` has resolved.
5. `Promise.all([fetchBoards, fetchUserSettings, fetchCurrentPlan])` (`Board.tsx:205`). `activeBoardId`
   is set from the result, so tasks wait on the **slowest of the three**.
6. `GET /boards/{id}/tasks` (`Board.tsx:261`); its `.finally` is the only thing that clears `loading`.

Steps 3→4→5→6 are strictly serial, and nothing is prefetched or speculatively started. On mobile that
is four sequential round trips *after* the HTML, plus the download and parse of the largest chunk in
the app — `Board.tsx` pulls in dnd-kit, tiptap, marked, dompurify and every modal, and `vite.config.ts`
sets no `manualChunks`, so there is no vendor split to share or cache separately.

Until all of that finishes the user sees centred `app.loading` text (`App.tsx:32`) — no skeleton, no
placeholder, no previous content.

## Why the cached-copy idea is the wrong first move

**A cached task snapshot cannot paint anything before the `Board` chunk has downloaded and parsed,
because the renderer *is* that chunk.** A cache therefore only compresses steps 5–6 of the six above.
It does nothing for steps 1–4, which on a cold mobile open are plausibly the majority of the wait.

Two duller changes compress that same span, with no staleness and nothing written to the device:

- starting the `Board` chunk in parallel with `/me` (gated — see A1) takes step 4 off the critical path;
- a single bootstrap response collapses steps 5 and 6 into one round trip.

Which leaves a client cache buying roughly **one round trip** over the dull fixes. Against that it
costs a read-only mode, cache invalidation across boards and status filters, a wipe-on-logout
obligation, and plaintext task content at rest.

There is also a tension that should be named rather than glossed: the scoping that makes client
persistence defensible on privacy grounds — `sessionStorage` — is **empty on a cold open in a new
tab**, which is precisely the case that prompted this note. Short-TTL `localStorage` is the only
variant that actually helps here, and it is exactly the variant carrying the real at-rest exposure.
"Scoped and short-lived" is not a free middle ground; it mostly trades the benefit away.

## Three things already in the codebase that reframe this

- **Per-entity watermarks already exist.** `GET /api/v1/boards/{boardId}/sync` (`SyncController.kt`,
  backed by the `backlog_task_watermark` and `plan_watermark` tables) returns `tasksChangedAt`,
  `tagsChangedAt`, `categoriesChangedAt`, `planChangedAt` and `appVersion`. A cached snapshot could be
  cheaply **validated** — "this is still current" — rather than displayed as stale. If a cache is ever
  built, this removes most of the read-only/staleness argument in the original idea, and that is worth
  recording even though the recommendation below is not to build one yet.
- **Conditional requests already work.** `ShallowEtagHeaderFilter` is registered on `/api/v1/*`
  (`WebConfiguration.kt:31`). Shallow, so a 304 saves bytes on the wire but not server work — the
  queries and decrypts still run.
- **The client already knows which board to load.** `localStorage['backlog.activeBoardId']`
  (`Board.tsx:52`) is read at bootstrap, so a speculative or bootstrap fetch has its key before
  `/boards` returns.

---

## Options

### A. Collapse the waterfall — no stale data, nothing stored

The recommended lane. A1 and A3 are **speculation, and speculation must be gated**, or they regress the
anonymous load. The cheapest honest gate is a non-secret `localStorage` flag written when `/me` returns
authenticated, cleared on logout and on the `auth:unauthenticated` 401 event `src/api.ts` already
dispatches — no server change, no new cookie, nothing sensitive in it. A first-time or signed-out
visitor has no flag and gets today's behavior byte for byte; so does every crawler and PageSpeed run.
The worst case for a stale flag (expired session) is one wasted chunk fetch that warms the cache.

- **A1 — speculative `Board` chunk, gated.** Start `import('./Board')` alongside `/me` instead of
  after it. Removes a full round trip *and* moves the largest download earlier so it overlaps the auth
  call. Probably the single biggest win available on mobile.
- **A2 — one bootstrap response.** *Anonymous-safe* (it is an authenticated endpoint). Collapse steps
  5 and 6 into a single call returning boards, settings, plan and the active board's
  tasks/tags/categories. Needs a rule for which board when the client sends no hint.
- **A3 — start fetching before React, gated.** An inline script in `index.html` firing the bootstrap
  request keyed on `backlog.activeBoardId`, so the network is busy during bundle parse. Strictly more
  invasive than A2 and only worth it if measurement shows parse time is material.
- **A4 — stop letting the plan delay tasks.** *Anonymous-safe.* `activeBoardId` currently waits on
  `Promise.all([...fetchCurrentPlan()])` (`Board.tsx:205`), so a slow `/plans/current` holds up tasks
  that do not depend on it. Resolving the active board from `fetchBoards()` alone is a small change to
  code that already runs behind auth.
- **A5 — split the `Board` chunk.** *Anonymous-safe, and pure win in both directions.* tiptap, marked
  and dompurify are drawer/detail concerns, not needed to paint the task list. Splitting them shrinks
  what a signed-in user downloads before first paint and cannot affect the anonymous entry chunk,
  since none of it is in there today.

### B. Perceived speed only — a skeleton

Replace the `app.loading` text with a board-shaped skeleton. Zero privacy cost, zero correctness risk,
no new failure mode, *anonymous-safe*. On mobile this is plausibly the largest felt improvement per
hour spent, and it composes with everything else. Listed as a serious option, not a consolation prize:
"feels slow" and "is slow" are different complaints and this note was prompted by the former.

### C. HTTP-layer caching

Give the tasks endpoint explicit `Cache-Control: private` plus proper conditional-GET handling, and
let the browser's own cache do the work — no bespoke cache code, and the data lives inside the site-data
lifecycle the browser already manages (cleared with site data, partitioned per origin) rather than in
storage we have to remember to wipe. `stale-while-revalidate` would be the interesting directive here,
but its behavior for `fetch()`-issued subresource requests is **not something to assume** — it needs
verifying against real browsers before anything is designed around it.

Note this cannot be done today without first fixing the missing `Cache-Control` described under
"Pre-existing issues" below. The Nginx side has since been read directly (`Itaypk/itayp-dev`,
`ansible/roles/nginx/templates/vhost.conf.j2`): the catch-all `location /` that serves `/api/**` does
not touch `Cache-Control` at all, so whatever the app sends is what the browser sees. That makes this
option a pure app-side change — no Ansible change is needed to set `private` on the tasks endpoint.

### D. Client-persisted snapshot (the original idea)

Covered above. Variants, worst to best on privacy: `localStorage` with everything; `localStorage` with
titles/metadata only and no descriptions or notes; `sessionStorage`. The value ranking is the exact
reverse, and the variant that helps cold open is the one with the real exposure. If it is ever built,
build it against `/sync` validation (see above) so it is *confirmed current* rather than *displayed
stale* — which also lets it skip the read-only mode the original idea assumed it would need.

### E. Service worker, without the rest of the PWA

Worth stating plainly because it is easy to conflate with the "full PWA" non-goal: SW response caching
is separable from offline support, the manifest and installability. A SW could serve a cached board
response instantly and revalidate behind it. But it has its own update-versioning problem — a stale SW
serving a stale shell after a redeploy — which the app currently handles with the `appVersion` field in
`/sync` and a refresh nudge. Two overlapping update mechanisms is real complexity for a handful of
beta users.

### F. Inject bootstrap JSON into `index.html`

Removes a round trip entirely by shipping the data with the document. Costs the static-file serving
path (`index.html` is currently served from the classpath by Boot's welcome-page handler and forwarded
to by `SpaForwardController`), makes the document per-user and uncacheable, and entangles the SPA shell
with rendering. Listed for completeness; the cost is out of proportion to one round trip.

### G. Make the server cheaper

Fix the N+1 below, and consider omitting `description` from the board-list payload (it is capped at
5000 chars and the list does not render it — the drawer does). Only worth doing for latency reasons if
measurement shows server time actually matters; the N+1 is worth fixing regardless.

### H. Do nothing

The honest case: if the server answers in tens of milliseconds and the payload is a few dozen KB, then
this is entirely a latency-and-bundle problem, and only A, B and A5 touch it. C, D, E and F would then
be complexity spent on the part that was never slow. **This is the default until there are numbers.**

---

## Recommendation

A ladder, in this order, stopping whenever it feels fast enough:

1. **Measure** (below). Everything after this is guesswork without it.
2. **B** (skeleton) and **A5** (chunk split) — both anonymous-safe, both cheap, neither needs a gate.
3. **A4** (don't let the plan block tasks) — small, contained, anonymous-safe.
4. **A1** (gated chunk speculation) — likely the largest single mobile win; needs the gate done
   carefully, with an explicit check that the anonymous entry chunk is unchanged.
5. **A2** (bootstrap response) — a real API addition; worth it if 2–4 leave a visible gap.
6. Re-evaluate **D** only if a gap remains after all of the above. The expectation is that it will not.

**C** stays parked behind its own prerequisite fix and a browser check on `stale-while-revalidate`.
**E** and **F** are documented and not recommended.

## Measure first

**Done — see "Measurements (2026-09-21)" at the end of this note.** The original decomposition is
kept below for reference; note that its first bullet's scaling hypothesis is stale (the N+1 was
fixed before the measurements were taken). The decomposition to attribute:

- **Server time** for `GET /boards/{id}/tasks` — how long, and how it scales with task count
  (the N+1 means it should scale worse than linearly; that is the thing to look for).
- **Payload size** for a realistic backlog.
- **Round-trip count and latency** on a real phone on mobile data, not a throttled desktop profile.
- **Chunk download and parse** for the `Board` chunk, separated from the network time.

The split between the last two and the first two decides the whole question: options C, D and G are
only worth their complexity if the server or the payload dominates, and the trace above suggests
strongly that they do not.

---

## Pre-existing issues noticed while tracing this

Not part of any option above, but all verified, and the first two touch this area directly. Items
marked *Fixed* were dealt with in a later pass; the rest are still open.

- **N+1 on the tasks endpoint.** *Fixed.* `jpa/BacklogTaskMapper.kt:21-22` dereferences the lazy
  `@ManyToOne category` and `@ManyToMany tags` (`jpa/BacklogTask.kt:50,54`) for every task, and there
  was no `@EntityGraph`, no `@BatchSize` and no `hibernate.default_batch_fetch_size` anywhere — a board
  of N tasks issued roughly 1 + N selects for the tag collections alone. `application.yaml` now sets
  `spring.jpa.properties.hibernate.default_batch_fetch_size: 100`, which collapses those into `IN (...)`
  batches: a board list is now a constant ~3 queries regardless of size. Set globally rather than as a
  per-query fetch hint because the same two associations are walked from the board list, the planner's
  cross-board reads, `/sync` and the external API. **Not covered by a test** — a query-count regression
  test would need `hibernate.generate_statistics` wired into the test profile; worth adding if this
  area is touched again.
- **Authenticated API responses carry no `Cache-Control` at all.** *Still open; now documented.*
  `SecurityConfiguration`'s `cacheControl { disable() }` turns Spring's writer off so the hashed assets
  can be cached, and nothing re-adds a header for `/api/v1/**`. Reading the real Nginx config confirms
  it: the four locations that do set caching (`/assets/`, `/`, `/index.html`, `/favicon.svg`) each
  `proxy_hide_header Cache-Control` and set their own; everything else — `/api/**` included — falls
  through the catch-all `location /`, which leaves the header alone. So task JSON ships with no
  `private` and no `no-store`. Harmless today (no shared cache sits between Nginx and the browser) but
  an accident rather than a decision, and option C's prerequisite.
- **The unhashed files in `tasker-frontend/public/` also got no `Cache-Control`.** *Fixed upstream, in
  `Itaypk/itayp-dev#25`.* They fell through the same catch-all, so `manifest.json`, `apple-touch-icon.png`,
  `favicon.ico` and the PNG favicons — all referenced from `index.html`'s `<head>`, so all on the
  cold-open path — plus `og-image.png` were served with no `Cache-Control` and left to browser
  heuristics. The `spa: true` branch now caches root-level unhashed assets for a day, via a regex
  anchored to one path segment (it must stay anchored: regex locations outrank the `location /assets/`
  prefix, so a looser pattern would quietly downgrade the immutable hashed assets). Verified against
  prod: `/favicon.ico`, `/favicon-32x32.png`, `/apple-touch-icon.png`, `/manifest.json` and
  `/og-image.png` all return `public, max-age=86400`, while `/assets/*` still returns
  `public, max-age=31536000, immutable`.
  That change also removed the template's `/favicon.svg` location, which matched nothing on this vhost —
  `index.html` references `/favicon.ico` and `/favicon-{16,32}x32.png`, and there is no `favicon.svg`.
- **`icons.svg` is unused.** *Correction to an earlier draft of this note, which claimed it was on the
  cold-open path.* `tasker-frontend/public/icons.svg` (5 KB) is referenced from nowhere in the
  frontend — not as an `<img>`, not as a CSS `url()`, and not as an SVG sprite (`<use href>` /
  `xlinkHref`). It is copied into the bundle and never fetched. That makes it dead weight to delete,
  not a caching question.
- **The comment at `SecurityConfiguration.kt:176` is half wrong.** *Fixed.* It said asset cache headers
  come from "Nginx + Spring resource handlers". There is no `addResourceHandlers` override and no
  `spring.web.resources.*` config anywhere; Nginx does all of it. The comment now spells out the actual
  per-location policy and the fall-through above.
- **Stale changeset comments.** *Fixed.* In `001-schema.xml`, `backlog_task.description` (line 244)
  and `backlog_task_change_event.task_title_snapshot` (line 473) both said "Encrypted under the user's
  DEK" while the code path for both is `BoardCryptoService` (the board's DEK). XML comments are not
  part of a changeset's parsed content, so editing them does not move the Liquibase checksum — which is
  why this was safe to change on an already-applied changeset. The other eight "user's DEK" comments in
  that file were checked against their services and are correct.
- **A second, stale Nginx config was checked in.** *Fixed — `nginx/` is deleted.* It had drifted
  badly against the live Ansible-owned config: it still named `tasks.itayp.dev` (the service moved to
  `backlog.fyi` with a `www` → canonical 301), carried no CSP, omitted the `limit_conn addr 50` override
  on `/assets/` that was added in prod after a cold SPA load tripped the per-IP connection cap, and its
  `nginx.conf` predated Brotli. None of the cache locations re-included `security-headers.conf`, so
  anyone reasoning from that copy would have concluded the assets ship without HSTS. Two sources of
  truth for cache headers is precisely the thing that would have bitten during option C.
- **Dead weight on the signed-in path.** The `<div id="prerendered-landing" hidden>` block in
  `index.html` (~2 KB of crawler-facing copy) ships to signed-in users and is never touched by JS.
  Minor, and it is on the cold-open path for everyone. Issue #256 already notes that this block's
  copy is out of date; that it is also dead weight is a second reason to revisit it.

---

## Measurements (2026-09-21)

Taken against production (`backlog.fyi`) on a real account and a throwaway test account, plus
Grafana Cloud for real-user server timings. **These supersede "There are no numbers today."**

### Method and its limits

- **Server time and payload**: the external API (`/api/external/v1/tasks?board=…&status=todo`)
  with a read token on the real account, over a warm connection so TLS is paid once and
  `time_starttransfer` ≈ RTT + server time. A `/me` call on the same connection is the RTT
  baseline. **Caveat**: that is not the SPA's endpoint — the DTO differs (`toExternalResponse`
  vs `BacklogTaskMapper`) and it paginates. Passing `board=` forces the same
  `backlogTaskService.getTasks` call the SPA endpoint makes, so server *time* is comparable;
  payload *bytes* are only indicative. The Grafana numbers below cover the real endpoint.
- **Waterfall**: Playwright/Chromium against prod, signed in via `POST /api/auth/demo-login`,
  with `fetch` patched and a `MutationObserver` installed *before* document load. Cache cleared
  between cold runs via CDP `Network.clearBrowserCache`. Desktop Linux, 8 cores, RTT ~80 ms.
  **"Cold" here means the HTTP cache was cleared, not a genuine first-ever visit** — V8's code
  cache was not verifiably cleared, and the 305 ms window below was identical cold and warm, which
  only makes sense if whatever dominates it survived the clear. Treat the 987 ms cold figure as a
  **lower bound** on a true first visit.
  **Caveat**: headless Chromium, and a desktop connection. Per this note's own instruction, the
  per-RTT mobile numbers still need a real phone; what is measured here is *structure*, which is
  device-independent, plus one finding that needs confirming in real Chrome (flagged inline).
- **Real-user server time**: `http_server_requests_seconds` from Grafana Cloud, 30-day window.

### 1. The server and the payload are not the problem

Real account, default board, `status=todo` — the actual cold-open call:

| call | n | ttfb (warm conn) | server time over baseline | JSON | brotli |
|---|---|---|---|---|---|
| `/me` (baseline) | — | 80–85 ms | — | 202 B | — |
| **tasks, `status=todo`** | **7** | **85–98 ms** | **~5–15 ms** | **5.2 KB** | **1.4 KB** |
| tasks, `status=archived` | 66 | 135–145 ms | ~55 ms | 48.5 KB | 7.9 KB |

~740 B/task, of which `description` is 23–28%.

Real-user server time for the **actual SPA endpoints** (Grafana, 30 d). Traffic is small
(~120 cold opens in 30 days), so the means are skewed by post-deploy JIT warmup and the max
column is the more useful signal:

| endpoint | avg | max | reqs/30 d |
|---|---|---|---|
| `/api/auth/me` | 13.8 ms | 99.9 ms | 120 |
| `/api/v1/boards/{boardId}/tasks` | 33.7 ms | 215.7 ms | 243 |
| `/api/v1/settings` | 36.9 ms | 186.7 ms | 139 |
| `/api/v1/boards` | 54.9 ms | 234.9 ms | 134 |
| `/api/v1/plans/current` | **60.6 ms** | **447.6 ms** | 141 |

`management.metrics.distribution.percentiles-histogram` is **not** enabled for
`http.server.requests` anywhere in `application.yaml`, so the max column is Micrometer's decaying
max, not a percentile. On 120–250 requests spread over 30 days, a single pathological request —
a cold JIT hit after a deploy, say — sets it. Read those as "one request was observed this slow",
not as a tail distribution. Enabling the histogram is the cheap fix if this area is revisited.

Server time is **~2% of the cold-open budget**. This settles options **C**, **D**, **F** and
**G** the way option **H** predicted: they are complexity spent on the part that was never slow.

### 2. The cold-open waterfall

Median of 3 cold runs (cache cleared) and 3 warm runs, times in ms from navigation start:

| step | cold | warm |
|---|---|---|
| document | 79 | 81 |
| entry bundle downloaded + booted, `/me` issued | 400 | 140 |
| `/me` resolved | 479 | 218 |
| **`import('./Board')` resolves, bootstrap fetch issued** | **786** | **523** |
| `boards` / `settings` / `plans/current` resolved | 882 | 611 |
| **tasks on screen** | **987** | **696** |

The anonymous entry bundle is **130,612 B brotli** across six files, and on a cold open all of
it must land before `/me` is even issued. Signed-in adds **80,367 B brotli**
(`Board-*.js` 49,289 + `Board-*.css` 7,528 + `config-*.js` 23,550).

### 2b. On a real phone (Pixel 6a, Chrome 153, Wi-Fi)

Driven over ADB wireless debugging + CDP. Device reports 8 cores, `deviceMemory` 4, `4g`,
`downlink` 1.45 Mb, `navigator.connection.rtt` 50 ms.

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| `/me` round trip | 94 ms | 89 ms | 88 ms |
| **`/me` resolved → `fetchBoards` issued** | **313 ms** | **303 ms** | **309 ms** |
| `boards`/`settings`/`plan` wave | 133 ms | 101 ms | 99 ms |
| tasks complete | 888 ms | 765 ms | 945 ms |

Two things to take from this. **The 305 ms window is identical on the phone** — a Pixel 6a is
several times slower than the desktop used above, and the number does not move, which is what
finally identified it (see below). And **the per-round-trip cost roughly matches the desktop**
(~90–130 ms vs ~80–95 ms), because this was measured over Wi-Fi.

**Still not measured: mobile data.** ADB *wireless* debugging requires the phone to stay on the
same LAN, so a cellular run needs USB debugging instead. On a cellular link at 150–250 ms RTT the
three serial waves would grow to roughly 450–750 ms and A2 would become the second-biggest lever
after the Suspense fix.

### 3. The largest single item is a 300 ms React Suspense fallback throttle

Between `/me` resolving and Board's bootstrap `useEffect` firing:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| cold | 310 | 306 | 307 |
| warm | 316 | 309 | 305 |

Median **307 ms, spread 305–316 ms across all six runs — identical whether the `Board` chunk was
downloaded (94 ms) or served from cache (0 ms)**. Inside that window:

- CPU (CDP `Performance.getMetrics`): `TaskDuration` +20–21 ms, `ScriptDuration` +11 ms.
  The main thread is **~94% idle**.
- Long tasks (observer installed pre-document): 0–1 per run, and the one that appears is *after*
  the tasks arrive.
- The DOM does not change at all: it goes 2,618 B → 2,417 B at `/me` resolution (landing removed,
  `RouteFallback` shown, 0 buttons), then nothing until 27,944 B / 63 buttons long after.

So it is not download, not parse, not evaluate, and not render. It is ~290 ms of idle sitting
inside the resolution of `import('./Board')` (`App.tsx:15`).

**This is the single largest item in the budget — larger than every round trip combined — and no
option in this note addresses it, because the trace above assumed that span was download + parse.**

**It is React's Suspense fallback throttle, and the arithmetic is exact.** On the desktop trace
the `RouteFallback` commits at **t=209** (DOM 2,618 → 2,417 B, 0 buttons, immediately after `/me`
resolves) and `fetchBoards` fires at **t=510**. **209 + 300 = 509.**

`react-dom` 19.3.0 defines `FALLBACK_THROTTLE_MS = 300`
(`cjs/react-dom-client.development.js:30031`, and inlined as
`globalMostRecentFallbackTime + 300 - now()` in `react-dom-client.production.js` — verified, so
this is the build that actually ships). When a Suspense fallback commits, React stamps
`globalMostRecentFallbackTime`; it then refuses to commit the resolved content until 300 ms have
elapsed, to avoid flashing a fallback on and off. The rest of the evidence agrees:

- **The window is 300 ms, not "about 300 ms".** 303/303/304 ms on the phone, 305–316 ms on desktop.
- **It is device-independent.** A Pixel 6a and an 8-core desktop produce the same number. Real CPU
  work would not.
- **Preloading the chunk does not help** (see below), because `React.lazy` suspends on its *first
  render* whether or not the module is already in the module map. The fallback commits, the
  timestamp is stamped, and the throttle applies regardless.

Measured on the phone, `import('/assets/Board-*.js')` kicked off at document start via
`Page.addScriptToEvaluateOnNewDocument`:

| | baseline | preloaded |
|---|---|---|
| gap | 303 / 303 / 304 / 306 ms | 305 / 307 / 305 / 304 / 306 ms |
| when `Board-*.js` was requested | t=189, 208 ms (*after* `/me` resolves) | **t=99, 107 ms** (*before* `/me` resolves) |

The second row is the control: it confirms the preload genuinely populated the module map ahead of
time rather than silently missing. It did, and the gap did not move.

**The irony is that the throttle is protecting against a flash this code deliberately does not
have.** `App.tsx:26-28` already documents that `RouteFallback` uses "the same markup as the
auth-bootstrap placeholder, so a cold load that is both fetching `/me` and fetching the board chunk
doesn't flicker between two different loading states." The user sees an identical placeholder
before and after the fallback commit — so the 300 ms buys nothing here and costs a third of the
warm cold-open budget.

**This is the single biggest available win, and it is not A1.** The fix is to stop committing a
Suspense fallback on that transition. Candidates, **in no particular order — the relative cost is untested**:
mark the auth-state update with `startTransition` (React keeps the current UI rather than showing a
fallback; note the current UI at that instant is already `RouteFallback` from the
`state.status === 'loading'` branch at `App.tsx:66`, so this is visually a no-op — but whether it
actually skips the fallback *commit*, and therefore the timestamp, is a React-internals question
this note has not tested); or resolve the `Board` module into state and render it without a
`Suspense` boundary in that position; or hoist the `Suspense` boundary so it is not re-entered when
`state.status` flips. Each needs verifying against the numbers above; reproducing the harness is
~20 lines of CDP.

**Remaining uncertainty**: the mechanism is inferred from React's source plus behavior, not from a
React-internals trace. Building the fix and re-measuring the gap is the cheapest confirmation.

### 4. What this does to the options

- **A1 (gated chunk speculation)** — **~78 ms, and measurably *not* a fix for the 305 ms window.**
  The preload experiment above is exactly A1's mechanism, and the gap did not move. A1 still saves
  the `/me` round trip on a cold visit (the chunk download overlaps the auth call), and that is
  worth proportionally more on a phone at 150–250 ms RTT — but it must not be sold as addressing
  the window. Fixing the Suspense throttle is a separate, larger and cheaper change.
- **A2 (bootstrap response)** — saves one round trip, **~89 ms of 987 ms (9%)**. Real, modest.
- **A4 (plan must not block tasks)** — **weakly supported, and worth doing anyway.** In the demo
  trace the three parallel calls were 83/85/81 ms, so the typical saving is ~6 ms. `/plans/current`
  is the slowest of the three by mean (60.6 ms) and has the single worst observed request
  (447.6 ms) — but see the caveat above: that is one observation, not a measured tail. The honest
  summary is that A4 buys a few ms typically and removes an unquantified worst case. It stays on
  the ladder because it is small, contained and anonymous-safe, not because the numbers demand it.
- **A5 (split the `Board` chunk)** — **its stated targets are stale; see the corrections below.**
  It can only help the cold download (49 KB brotli, ~94 ms), not the 305 ms window, which is
  CPU-free.
- **B (skeleton)** — unchanged and still the best value per hour: it covers the entire
  **~987 ms** of blank screen on a cold open, whatever the 305 ms turns out to be.
- **C, D, F, G** — server time is 2% of the budget. Confirmed not worth their complexity.

### 5. How server time scales with task count

Run on a throwaway test account (empty board, then tasks created in increments via the external
API's `POST /tasks`), same warm-connection method, `/me` on the same connection as the baseline.
The external list caps at `limit=200`, but `BacklogTaskService.getTasks` loads the whole board
before paginating in memory — so the 500-task row still does 500 tasks' worth of server work.

| tasks on board | returned | `/me` median | tasks median | server delta | JSON bytes |
|---:|---:|---:|---:|---:|---:|
| 0 | 0 | 79.5 ms | 78.7 ms | −0.8 ms | 40 |
| 25 | 25 | 76.3 ms | 121.8 ms | 45.5 ms | 19,006 |
| 50 | 50 | 75.7 ms | 123.9 ms | 48.2 ms | 37,981 |
| 100 | 100 | 76.3 ms | 102.9 ms | 26.6 ms | 75,933 |
| 250 | 200 | 99.1 ms | 144.4 ms | 45.3 ms | 151,932 |
| 500 | 200 | 74.3 ms | 131.3 ms | 57.0 ms | 151,932 |

**Server time is essentially flat in task count.** From 25 to 500 tasks the delta stays in a
27–57 ms band with no trend — a twentyfold increase in rows buys no measurable slope. The N+1 fix
holds, and the superlinear curve the original "Measure first" section told us to look for does not
exist. What there *is* is a fixed ~45 ms step between 0 and 25 tasks — a per-request setup cost
(board DEK unwrap and decryption setup are the obvious candidates) that does not grow afterwards.

Payload, by contrast, is exactly linear at **~760 B/task**. A 500-task board would be ~380 KB of
JSON (~60 KB brotli) if it were not capped — which is the one place a large backlog would start to
matter, and it argues for pagination or for dropping `description` from the list payload long
before it argues for any caching option.

For calibration: the real account this note was prompted by has **7 todo tasks** (and 66 archived).
It is nowhere near the range where any of this matters.

### 6. Corrections to claims in this note

- **A5's targets are wrong.** tiptap is **already** split out: `TaskDrawer.tsx:19` does
  `const NoteEditor = lazy(() => import('./NoteEditor'))`, and the build emits it as its own
  501 KB chunk that the `Board` chunk does not contain. And marked/dompurify are **not**
  "drawer/detail concerns": `MarkdownRenderer` is rendered by `TaskLine.tsx:166` and
  `PostItNote.tsx:120` — the task-list rows themselves render the description as markdown. They
  are on the paint-the-list path and cannot be split out without changing what the list shows.
  What remains of A5 is the modals and dnd-kit, which is a much smaller prize than stated.
- **The N+1 scaling hypothesis under "Measure first" is stale.** It predates the
  `hibernate.default_batch_fetch_size: 100` fix recorded in the pre-existing-issues section.
  There is no superlinear curve to look for; see the sweep below.
- `vite.config.ts` still sets no `manualChunks` — confirmed. (The build is rolldown, via Vite 8.)

### 7. Still missing

- **Per-RTT latency on a real phone on mobile data.** Everything above was measured from a desktop
  at ~80 ms RTT. The *structure* (round-trip count, serialization order, the 305 ms window, the
  flat server curve) is device-independent and carries over; the *absolute* numbers do not. On a
  phone at 150–250 ms RTT the three serial round trips grow to 450–750 ms and the entry-bundle
  download grows with them, which would make A1 and A2 worth more than the percentages above.
- **A cellular run.** Everything on-device so far was over Wi-Fi; see section 2b.
- ~~**Whether the anonymous entry bundle can shrink.**~~ **Answered — see "Bottom line" below.**
  It is ~90% framework and cannot shrink without leaving React, React Router or i18next.

## Verification of the fix (2026-09-21, same day)

Commit `d248926` removed the Suspense boundary from the signed-in path: `lazyComponent.ts`
resolves the board module itself and renders `RouteFallback` directly, so no fallback ever
commits and nothing stamps `globalMostRecentFallbackTime`. Deployed to production
(`index-e3lVPyDe.js`) and re-measured on the same Pixel 6a with the same CDP rig.

**Warm (HTTP cache populated), the directly comparable case:**

| `/me` resolved → `fetchBoards` issued | before | after |
|---|---|---|
| Pixel 6a | 313 / 303 / 309 ms | **8 / 10 / 7 / 42 ms** |
| tasks complete | 765 / 888 / 945 ms | **450 / 464 / 427 / 463 ms** |
| board painted | — | 489 / 495 / 457 / 493 ms |

The window is gone. Time-to-tasks on the phone roughly halved, because the whole downstream
waterfall shifts earlier rather than just the one window closing. The 42 ms outlier is the single
run where the board chunk actually had to be revalidated (8 ms) rather than served from memory.

**Cold (`Network.clearBrowserCache` before each navigate):**

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| entry chunk | 127→435 ms | 116→390 ms | 106→373 ms |
| `/me` resolved | 577 ms | 618 ms | 530 ms |
| **`/me` → `fetchBoards`** | **138 ms** | **115 ms** | **183 ms** |
| `Board-*.js` (49,588 B) | 582→681 ms | 620→719 ms | 537→701 ms |
| board painted | 1090 ms | 1001 ms | 969 ms |

**This is what makes A1 worth building now.** The residual gap is no longer a timer — it is
*exactly* the board chunk download (99 / 99 / 164 ms against a gap of 138 / 115 / 183 ms). Before
the fix a preload moved that request 100 ms earlier and bought nothing, because the 300 ms throttle
swallowed it; the preload control experiment in section 3 is what proved that. With the throttle
gone, the same preload is worth the full chunk fetch on a cold open, and ~0 on a warm one.

The constraint A1 has to respect is `tasker-frontend/CLAUDE.md`'s anonymous first-paint budget:
the board chunk must not be requested by a visitor who is not signed in, which rules out a
`<link rel="modulepreload">` in `index.html` and rules out starting the import above `AuthShell`'s
early returns. It needs a signed-in hint that is readable before `/me` answers. That was built,
deployed and measured — see the next section.

## A1 was built, measured and reverted (2026-09-21)

`7e69231` gated the preload on a one-bit `localStorage` hint written whenever auth resolved, so a
returning signed-in browser started the chunk in parallel with `/me` while an anonymous visitor's
first paint stayed untouched. Because the gate is a storage bit, clearing it disables the preload
for a single load — which makes a proper interleaved A/B possible on one deployed build, same
phone, same minutes, alternating arms. Eleven pairs, cold cache each time:

| | OFF (n=11) | ON (n=11) |
|---|---|---|
| `/me` resolved → `fetchBoards` | 224 ms | **10 ms** |
| `/me` round trip | 98 ms | **247 ms** |
| **board painted** | **1174 ms** | **1173 ms** |

(medians; the chunk request moved ahead of `/me` in 11/11 ON loads and 0/11 OFF loads, so the arms
really are what they claim to be.)

**The preload does exactly what it was designed to do and buys nothing.** The gap closes as
predicted, and `/me` absorbs precisely what the gap gives up. The reason is that a cold open on
this link is **bandwidth-bound, not latency-bound**: fetching 49.6 kB concurrently with `/me`
doesn't create parallelism, it reorders one queue. The paired median was −56 ms against a
run-to-run spread of −652 to +450 ms, and the apparent wins were driven by outliers in the OFF
arm rather than by a consistent gain.

Reverted in `821ec45`. It left a `localStorage` side-channel coupled to auth state, paying for
nothing. It is recoverable from `7e69231` if a bandwidth-rich, high-RTT link ever makes it worth
re-testing — that is the regime where it would win.

**This retires A1 and reframes the rest of the note.** Both remedies tried so far moved *when*
bytes are fetched; only the Suspense fix helped, because it removed a timer rather than a
transfer. What is left on a cold phone open is a byte- and round-trip-serial chain —
entry chunk ~390-480 ms → `/me` ~650 ms → boards wave ~880 ms → tasks ~1100 ms — where every
stage waits on the one before. The levers that can still move it are the ones that **remove a
round trip or remove bytes**, not the ones that re-order them: A2 (one bootstrap response instead
of the `/me` → boards → tasks chain) and shrinking the 240 KB entry chunk that must land before
`/me` is even issued. Re-ranking the options against that is the next open question.

## Bottom line (2026-09-21)

**One fix shipped, and it was the one that mattered.** Removing the Suspense throttle
(`d248926`) roughly halved time-to-tasks on a real phone — 765–945 ms → 427–464 ms warm. It was
~70 lines, no new dependencies, no new i18n keys, and it worked because it deleted a *timer*
rather than moving bytes around.

**Everything else measured turned out not to be worth doing**, and that is the useful part of this
note:

- **The server was never the problem.** ~2% of the budget, and flat from 25 to 500 tasks. That
  settles C, D, F and G exactly as option H predicted.
- **A1 was built, deployed, A/B'd over eleven interleaved pairs, and reverted.** It closed the gap
  it targeted (224 → 10 ms) and moved board-painted by one millisecond, because a cold open on a
  phone link is bandwidth-bound: fetching the chunk alongside `/me` reorders a queue rather than
  creating parallelism.
- **The eager bundle cannot meaningfully shrink.** A `rollup-plugin-visualizer` run over the
  production build settles the last open question in "Still missing":

  | eager file | brotli | composition (rendered share) |
  |---|---|---|
  | `index-*.js` | 69.8k | **react-dom 88%**, LoginPage 3%, react-i18next 3% |
  | `authApi-*.js` | 25.2k | i18next 61%, `locales/en` 25%, `api.ts` 9% |
  | `chunk-OB3PAWPO` | 13.0k | react-router 98% |
  | `index-*.css` | 13.0k | |
  | `jsx-runtime-*.js` | 5.3k | react 58%, react-i18next 35% |
  | `rolldown-runtime` | 0.3k | |
  | **total** | **~127k** | lands before `/me` is even issued |

  **Application code is 10% of that graph** (84,939 B rendered of 854,785); the other 90% is
  framework. `react-dom-client.production.js` alone is 88% of the main chunk. There is nothing
  dumb in there to delete — the candidates are replacing React Router with a hand-rolled matcher
  (~12k brotli, touches every route plus `SpaForwardController`) or splitting the English catalogue
  into namespaces (~4–5k, fights the four-locale parity gate in `catalog.test.ts`). Both are
  large-effort, low-yield, and neither is recommended.

**So this direction is parked.** The cheap, high-yield change has been made. What remains — A2's
bootstrap endpoint, a bundle rewrite, a client-side cache — is high-cost or high-risk for a few
hundred milliseconds on cold opens only, at a scale of a handful of beta users. The remaining
levers are written down above if the picture changes; nothing here needs doing now.

**If this is ever picked up again, start here:**

- **B (a skeleton) is still untouched and still the best value per hour.** It is the one item on
  the original ladder that was never built, it is anonymous-safe, and it addresses the felt
  complaint that prompted this note rather than the measured one.
- **A2** is the only remaining structural lever, worth ~200–450 ms on a cold phone open.
- **A cellular measurement** is still missing (everything on-device was over Wi-Fi). On a
  150–250 ms RTT link the serial waves grow and A2's value grows with them.
- **Enable `management.metrics.distribution.percentiles-histogram`** before trusting any Grafana
  tail number here; the max column above is a decaying max, not a percentile.

