# Faster initial display of tasks

Exploration note on what a signed-in user waits for between opening the app and seeing their tasks,
and what could be done about it.

Status: **exploration only** — options, tradeoffs and a recommendation, not a build plan. No
implementation should start from this document without a separate follow-up decision. Supersedes the
one-line idea at `docs/IDEAS.md` ("Smoother loading — show a cached copy in read-only mode…").

The trigger is felt slowness, specifically on **mobile cold open**, and the `IDEAS.md` framing: it is
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
  built, this removes most of the read-only/staleness argument in `IDEAS.md`, and that is worth
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
visitor has no flag and gets today's behaviour byte for byte; so does every crawler and PageSpeed run.
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
but its behaviour for `fetch()`-issued subresource requests is **not something to assume** — it needs
verifying against real browsers before anything is designed around it.

Note this cannot be done today without first fixing the missing `Cache-Control` described under
"Pre-existing issues" below.

### D. Client-persisted snapshot (the `IDEAS.md` idea)

Covered above. Variants, worst to best on privacy: `localStorage` with everything; `localStorage` with
titles/metadata only and no descriptions or notes; `sessionStorage`. The value ranking is the exact
reverse, and the variant that helps cold open is the one with the real exposure. If it is ever built,
build it against `/sync` validation (see above) so it is *confirmed current* rather than *displayed
stale* — which also lets it skip the read-only mode `IDEAS.md` assumed it would need.

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

There are no numbers today, which is why this note recommends no build. The decomposition to attribute:

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

Not part of any option above, but all verified, and the first two touch this area directly.

- **N+1 on the tasks endpoint.** `jpa/BacklogTaskMapper.kt:21-22` dereferences the lazy
  `@ManyToOne category` and `@ManyToMany tags` (`jpa/BacklogTask.kt:50,54`) for every task. There is no
  `@EntityGraph`, no `@BatchSize`, and no `hibernate.default_batch_fetch_size` in `application.yaml`.
  A board of N tasks therefore issues roughly 1 + N selects for the tag collections alone (category
  proxies at least dedupe through the persistence context). This is on the exact endpoint under
  discussion and is the cheapest real server-side win available.
- **Authenticated API responses carry no `Cache-Control` at all.** `SecurityConfiguration.kt:177`
  disables Spring Security's cache-control writer so that static assets can be cached, and nothing
  re-adds a header for `/api/v1/**`. Task JSON therefore ships with no `private` and no `no-store`.
  Harmless today — there is no shared cache between Nginx and the browser — but it is an accident
  rather than a decision, and option C would have to fix it first.
- **The comment at `SecurityConfiguration.kt:176` is half wrong.** It says asset cache headers come
  from "Nginx + Spring resource handlers". There is no `addResourceHandlers` override and no
  `spring.web.resources.*` config anywhere; Nginx does all of it.
- **Stale changeset comment.** In `001-schema.xml`, `backlog_task.title` (line 240) is correctly
  commented "Encrypted under the board's DEK" while `description` (line 244) says "under the user's
  DEK" — same table, and both actually go through `BoardCryptoService`. Comment only, no behaviour
  change, but misleading for exactly the kind of work this note describes.
- **A second, stale Nginx config is checked in.** `nginx/sites-available/tasks.itayp.dev` still lives in
  this repo while the root `CLAUDE.md` states the live config is owned by the separate `itayp_dev`
  Ansible repo. Two sources of truth for cache headers is precisely the thing that would bite during
  option C.
- **Dead weight on the signed-in path.** The `<div id="prerendered-landing" hidden>` block in
  `index.html` (~2 KB of crawler-facing copy) ships to signed-in users and is never touched by JS.
  Minor, and it is on the cold-open path for everyone. `docs/IDEAS.md` already notes that this block's
  copy is out of date; that it is also dead weight is a second reason to revisit it.
