# Launch plan: from one user to the first hundred

Status: proposal, 2026-09. Companion to `docs/SPEC.md` (what the product is) and `docs/IDEAS.md`
(what's deferred). This file is about distribution, not features.

## The honest starting point

The product has one active user: its author. That means two things are unproven, and they are
not the same problem:

1. **Acquisition** — can strangers be made to sign up? This is what "put it out to the world"
   usually means, and it's the easier half.
2. **Activation and retention** — does a stranger add tasks, run one planning session, and come
   back for a *second* week? Nothing in the repo has tested this yet. A launch post that brings
   500 sign-ups who each bounce at the empty board burns the one shot you get on most channels
   (Hacker News and Product Hunt in particular reward a first launch and ignore a second).

So the plan is sequenced: **prove activation with 10–20 warm users first, then go wide.** The
temptation is to skip step one because step two is more exciting. Don't.

A second honest note: "useful, nice UI, free" is table stakes, not a pitch. Every to-do app says
that. The things here that are genuinely unusual are:

- **Capture from a photo or a voice note in Telegram** — forward a school notice, get a task
  draft back. This demos in 10 seconds and is the hook.
- **A planner that pushes back** on an over-full week, and turns agreement into calendar time
  blocks.
- **Privacy that's real**: titles/descriptions encrypted at rest, no ads, no data sale, solo
  non-commercial project. This matters to exactly the communities that will actually try an
  indie tool.
- **An API written for AI assistants** (`/external-api/SKILL.md`, `llms.txt`,
  `/.well-known/api-catalog`) — a niche, but a loud and early-adopting niche.

Lead with the first one. Everything else is supporting cast.

## Who it's for (pick one to start)

The FAQ already says it: *people running their own life*, not teams. Narrow further for launch,
because a message aimed at everyone lands with no one. Two candidates, ranked:

1. **Parents / people juggling household admin** — the "photo of the school notice" story is
   theirs, and the shared-board feature (family shopping list, school runs) is built for them.
   Hard to reach in one place online, but the warm network is full of them.
2. **Developers and "productivity nerds"** — reachable in bulk (HN, Reddit, indie-hacker
   circles), sympathetic to a solo open project, will try the API. Higher churn, but they're the
   ones who write the posts that bring cohort 1.

Use cohort 2 for the public launch (they're where the channels are) and cohort 1 for the warm
beta. Localization already skews the geography: `he`, `ru`, `ar` plus Telegram-first means the
Hebrew- and Russian-speaking productivity communities are a natural first market — Telegram is
mainstream there in a way it isn't in the US, so "sign in with Telegram" is a feature rather than
a hurdle.

## Phase 0 — readiness (before anyone else signs up)

Ordered by "would embarrass you on launch day", most first. Each of these is a small PR.

- [ ] **Fix `tasker-frontend/index.html` meta/OG/Twitter/JSON-LD copy.** Tracked in
      `IDEAS.md` already: it still describes the old Telegram-only flow and old headline. Every
      share link on every channel below renders this preview. Add a real OG image (1200×630)
      showing the product, not a logo.
- [ ] **Make the demo/sandbox the default call-to-action** on the landing page. Nobody signs
      in to a product they haven't seen. The demo user already exists (24-h TTL, `AiTier.DEMO`);
      make sure it lands on a seeded board with a few realistic tasks and a "try the planner"
      nudge, not an empty screen. Seed data must be localized (four catalogs).
- [ ] **Decide what happens at user 51.** `TASKER_AI_TIER_CAP_MAX_GRANTED_USERS` defaults to
      50; beyond that, real sign-ups get the `DEMO` tier (100k tokens / 30 d). That's the right
      safety valve, but the FAQ says "free with usage limits" without saying what they are.
      Either raise the cap ahead of launch with a matching OpenRouter top-up, or write the limit
      into the FAQ so it isn't a surprise. Set a Grafana alert on OpenRouter spend either way.
- [ ] **Abuse surface review, 30 minutes.** A public launch is the first time strangers will
      point scripts at the free LLM. Confirm: demo-login rate limit per IP, magic-link rate
      limit, `TASKER_EMAIL_BLOCKED_DOMAINS` populated with the common disposable-mail domains,
      and that the unclaimed-account cap (shipped in #190) is actually on in prod.
- [ ] **Instrument the activation funnel** (see *Measuring* below). Without this, nothing in
      phase 1 teaches you anything.
- [ ] **A 30–60 second screen recording**: Telegram photo → task draft → weekly planning
      conversation → calendar invite arrives. This one asset gets reused in every channel. A
      GIF of the first 10 seconds is the thumbnail.
- [ ] **Onboarding email, day 1 and day 7.** You already have the auth SMTP sender and
      localized templates. One email after sign-up ("here's how to add your first task from
      Telegram"), one a week later ("did you run a planning session?"). Keep it two emails; a
      drip sequence is over-engineering at this scale.
- [ ] **Decide on the license** (`IDEAS.md` mentions AGPL; there is no `LICENSE` file). This is
      a launch decision, not a legal footnote: "open source, self-hostable, AGPL" is a
      *channel* — it unlocks r/selfhosted, Show HN framing, and the awesome-list ecosystem —
      and it matches the "no investors, your data is yours" story. Cost: some support burden
      from self-hosters, and you need a `README` that honestly says self-hosting requires a
      Telegram bot, an OpenRouter key and two SMTP accounts. If you'd rather not, launch as
      "free, source-available later" and revisit. Don't launch with the question open.

Explicitly **not** blockers, however tempting: Google login and Google Calendar sync. They're
the most-requested features you'll hear about in phase 1, and that's fine — "coming, here's how
invites work today" is an honest answer, and hearing the request from real users is the point of
phase 1. Building them first delays learning by months.

## Phase 1 — warm beta (weeks 1–3, target 10–20 real users)

Goal: **five people who complete a second planning session.** Not sign-ups; second sessions.

- Personally invite 20–30 people: friends, family, ex-colleagues, the LinkedIn network already
  linked from `/about`. One-to-one messages, not a broadcast. Ask for one thing: run one weekly
  planning session and tell you what was confusing.
- Sit with two or three of them for their first 15 minutes (screen share is fine). You will find
  the three onboarding problems that no amount of solo testing reveals.
- Weekly: look at the funnel numbers, fix the biggest drop-off, repeat. Don't add features.
- Collect three short quotes you're allowed to use publicly. Social proof for phase 2.

Exit criterion: 5 second-week users and no known "I couldn't figure out X" blocker. If you can't
get there with people who like you, strangers won't get there either; go back to phase 0.

## Phase 2 — public launch (weeks 4–6)

One channel per week, so each gets a full push and you can attribute traffic.

### Week 4: communities where the story fits

Post where the pitch is native, in this order of expected yield:

- **Hebrew-speaking tech/productivity groups** (Facebook groups, Telegram channels, the Israeli
  dev community). You're local, it's in Hebrew, Telegram-first is normal. Likely your best
  channel per hour spent.
- **Russian-speaking productivity/Telegram-bot communities** for the same reason. Needs a native
  speaker to review the copy.
- **Reddit**: r/productivity, r/getdisciplined, r/Telegram, r/selfhosted (only if licensed),
  r/SideProject. Read each sub's self-promotion rule first; several require a "here's what I
  learned" framing rather than a link drop, and the honest solo-non-commercial story is exactly
  that framing.
- **Indie Hackers / dev.to / Hashnode**: a build-in-public post. The interesting technical
  angles for that audience: envelope encryption of user content with an LLM in the loop,
  Telegram OIDC instead of the widget, an API designed for agents (`llms.txt`, RFC 9727).

### Week 5: Show HN

Title shape: *"Show HN: Backlog.fyi – a weekly planner where an AI helps you commit, not just
capture"*. First comment from you: what it is, why you built it, that it's solo and
non-commercial, what's encrypted, what's not built yet (calendar read). HN rewards candor and
punishes hype; the `/about` page is already the right tone.

Post Tuesday–Thursday, morning US Eastern. Be online for the next 6 hours to answer every
comment. Have the demo working and the AI cap raised that day; an HN front page can be
1–5k visitors, and if 10% try the demo that's the token budget for a month.

### Week 6: Product Hunt (optional)

Lower-quality traffic than HN for this kind of product and a lot of prep for one day. Do it
only if you enjoy it. If you do: a hunter with a following matters more than the listing.

### Ongoing: directories and discoverability (an afternoon, once)

- Submit to AlternativeTo (as an alternative to Todoist, TickTick, Motion, Reclaim), and the
  tool directories that still get search traffic (Toolify-style AI lists, "awesome-selfhosted"
  if licensed, "awesome-telegram-bots").
- The `/external-api/SKILL.md` and `llms.txt` are already a discoverability play for AI
  assistants; add the API to the public MCP/tool registries as they stabilize. Small but
  compounding.

## Phase 3 — content that compounds (month 3+)

Launch traffic decays in a week. What keeps trickling is search and referral:

- **Three or four evergreen posts** on the domain, each answering a query people actually type:
  "weekly planning routine that sticks", "time-blocking vs to-do lists", "add tasks from
  Telegram", "capture tasks from photos". Link the demo from each. This is the SEO the sitemap
  and `llms.txt` were built for.
- **A changelog / "what's new" page** and a monthly "building Backlog.fyi" note. Cheap,
  shows the project is alive, and it's the thing early users forward to friends.
- **Referral inside the product**: the board-invite flow is already a viral loop for cohort 1
  (a shared board needs a second person). Make the invite email good and mention it on the
  empty-board screen.

## Measuring: the five numbers that matter

Add one Prometheus counter per step (the app already has Micrometer and Grafana) or a tiny
`user_events` table — either is fine, the point is that they exist before phase 1.

| Step | Event | Why |
| --- | --- | --- |
| Visit → demo | demo login | Is the landing page doing its job? |
| Demo/sign-up → first task | first task created | Is the empty board understandable? |
| First task → first planning session | planning session completed | Is the core loop reachable? |
| First → second planning session | second session, ≥5 days later | **The retention number.** |
| Sign-up source | `?ref=` / UTM on every link you post | Which channel is worth repeating? |

Plus OpenRouter spend per week, because it's the one number that can end the project.

Review weekly. Watch the second-session rate more than the top of the funnel.

## Budget and risk

- Money: OpenRouter prepaid balance is the only variable cost. Decide a monthly ceiling now and
  put the number in the FAQ if it changes the promise. No paid ads; at this scale they'd tell
  you nothing you can't learn from 20 warm users.
- Time: roughly one focused evening per week for phase 0 items, then a solid launch day.
  The costliest mistake is spreading a launch over a month of half-posts.
- Single-instance VPS with acceptable downtime is fine for this traffic. Check that the
  in-memory rate limiter's thresholds don't lock out an HN spike coming through one NAT.
- Support load: point everything at the in-app feedback form and the support address; don't
  open Discord/Telegram community chats until there are 50+ users who want one.

## What not to do

- Don't build Google Calendar first "so it's complete". Launch with email invites; let demand
  prove the priority.
- Don't launch on HN or PH before the warm-beta exit criterion. You only get one first launch.
- Don't add a pricing page, teams, or "Pro" tier. Free-and-capped is a stronger story right now
  than free-with-an-upsell.
- Don't spread across ten channels in one week; you won't be able to tell which worked.

## Sequencing summary

| When | What | Done when |
| --- | --- | --- |
| Now | Phase 0 checklist | OG preview correct, demo seeded, funnel instrumented, license decided |
| Weeks 1–3 | Warm beta | 5 users with a second planning session |
| Week 4 | Community posts (he/ru first, then Reddit, IH) | Attribution shows which works |
| Week 5 | Show HN | Posted, answered, cap held |
| Week 6 | PH (optional), directories | Listed |
| Month 3+ | Evergreen content, changelog, invite loop | Steady organic sign-ups |
