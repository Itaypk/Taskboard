# Handoff: Backlog.fyi — Welcome Page (unauthenticated landing)

## Overview
This is the redesigned **welcome page** shown to unauthenticated visitors of Backlog.fyi, a
personal task planner. The page's job is to (1) communicate what the product does, (2) convey
the brand's playful, handmade "corkboard" personality, and (3) drive a single primary call to
action: sign up / log in.

The chosen direction is **"The Corkboard"** — the page is composed like a physical pinboard.
A large yellow sticky note holds the headline + CTA in the center; four smaller tilted sticky
notes in the margins carry the value props.

---

## About the Design Files
The files in `reference/` are a **design prototype built in HTML/React + inline CSS**. They are
a precise reference for *look and behavior* — **not** production code to drop into the app.

Your task: **recreate this design in Backlog.fyi's existing front-end environment**, using its
established component patterns, styling system, routing, and auth library. If the project has a
component library / design tokens, map the values below onto them rather than copying the inline
styles verbatim. If no front-end environment exists yet, choose the framework that best fits the
rest of the codebase and implement there.

The prototype was authored as React (via in-browser Babel) for previewing only. Treat the JSX as
pseudo-code for structure and the CSS as the source of truth for visual values.

### Files in this bundle (`reference/`)
- **`Welcome — Corkboard.html`** — host page. Loads fonts, mounts the component, and contains the
  **scale-to-fit script** (see "Responsive behavior").
- **`welcome-corkboard.jsx`** — the page component: layout, hero note, satellite notes, the login
  modal, the icons, and the full `cb` style object (all measurements/colors live here).
- **`corkboard.css`** — global tokens, `.sticky` note styling (incl. peeled-corner pseudo-element),
  `.logo-pill`, `.btn`, `.tape`, `.photo-ph` placeholder.
- **`tweaks-panel.jsx`** — the in-prototype "Tweaks" control panel. **Do NOT ship this.** It exists
  only so the designer could toggle options live. See "Tweaks → final decisions" — bake in the
  chosen defaults and drop the rest.

---

## Fidelity
**High-fidelity.** Colors, typography, spacing, copy, and interactions are final. Recreate the UI
faithfully. The one thing that is intentionally *not* final is the **imagery** — see "Assets".

---

## Tweaks → final decisions (IMPORTANT)
The prototype exposed several options via a Tweaks panel so the designer could decide. **These are
now decided — implement only the chosen values, and remove the toggle machinery:**

| Tweak | Options explored | **Ship this** |
|---|---|---|
| Login style | single button → modal, vs. inline buttons | **Single "Get started" button that opens a modal** |
| Channels offered | Telegram-only / +Google / +Email | **Telegram + Google + Email** (built to grow — see note) |
| Hero note color | yellow / peach / pink / blue / mint | **Yellow `#f3d96b`** |
| Hero note tilt | −3°…+3° | **−1.2°** |
| Headline | editable | **"Tasks you keep / actually doing."** |
| Show side notes | on/off | **On** (all four) |

The login modal was specifically chosen (over inline buttons) **because more sign-in providers may
be added later** — build the channel list as data-driven so adding a provider is a one-line change.

---

## Layout

The design is built on a fixed **1320 × 860** "stage" that is **scaled to fit** the viewport (see
Responsive behavior). All coordinates below are in stage space.

```
┌─────────────────────────────────────────────────────────────┐
│  [logo-pill]                      How it works · Privacy · Log in│  ← nav (abs top, 26px 44px)
│                                                                 │
│   ┌──────────┐                                ┌──────────┐      │
│   │ pink note│        ┌─────────────────┐     │ blue note│      │  ← satellites in margins
│   │ (problem)│        │   HERO NOTE     │     │ (method) │      │
│   └──────────┘        │  (yellow,       │     └──────────┘      │
│                       │   centered)     │                       │
│   ┌──────────┐        │  headline + CTA │     ┌──────────┐      │
│   │peach note│        └─────────────────┘     │ mint note│      │
│   │ (payoff) │                  🍍            │ (privacy)│      │
│   └──────────┘                                └──────────┘      │
│                                                                 │
│  Status · work in progress           Terms · Privacy · © 2026   │  ← footer (abs bottom)
└─────────────────────────────────────────────────────────────┘
```

- **Outer viewport**: `position: fixed; inset: 0; display: grid; place-items: center;`
  Background radial gradient `radial-gradient(130% 100% at 50% 25%, #efe6cd 0%, #e6dcbd 55%, #ddcfa6 100%)`.
- **Stage**: `position: relative; width: 1320px; height: 860px; transform-origin: center;`
  A faint paper-grain overlay sits on top (`aria-hidden`, two tiny radial-dot patterns at ~5% opacity).
- **Hero note**: absolutely centered — `left: 50%; top: 130px; transform: translateX(-50%);
  width: 600px; z-index: 3`.
- **Satellite notes** (`z-index: 1`, behind hero) are pinned to the margins so they **never overlap
  the hero** (verified ~40px gutters):
  - Pink (problem): `left: 8px;  top: 70px;  width: 250px; rotate(-5deg)`
  - Peach (payoff):  `left: 36px; top: 332px; width: 256px; rotate(3.5deg)`
  - Blue (method):   `right: 8px;  top: 58px;  width: 262px; rotate(4.5deg)`
  - Mint (privacy):  `right: 30px; top: 338px; width: 256px; rotate(-4deg)`
- **Nav**: `position: absolute; top:0; padding: 26px 44px; justify-content: space-between; z-index:5`.
- **Footer**: `position: absolute; bottom:0; padding: 20px 44px; border-top: 1px solid rgba(120,90,40,0.12)`.

---

## Components

### Logo pill (`.logo-pill`)
- Dark rounded pill, cream text, two small cream dots flanking the wordmark (`::before`/`::after`,
  4px circles, opacity .85).
- `background:#1c140a; color:#fdf7e2; padding:7px 14px 8px; border-radius:9px;`
- Font: Newsreader, **italic**, weight 600, 18px, letter-spacing −0.01em.
- Box-shadow `0 2px 8px rgba(0,0,0,0.18)`. Content: `Backlog.fyi`.

### Sticky note (`.sticky` + color modifier)
The signature element. Reused for satellites and (with a tweak) login buttons.
- `padding:16px 18px 20px; border-radius:2px 2px 14px 2px;` (note the **larger bottom-right radius**
  — the "peeled" corner).
- Shadow (stacked, soft): `0 1px 1px rgba(70,50,20,.06), 0 4px 10px rgba(70,50,20,.10), 0 14px 28px -8px rgba(70,50,20,.18)`.
- **Peeled-corner** via `::after`: a 20×20px element bottom-right combining a diagonal shadow gradient
  with the note's darker "edge" color. See `corkboard.css` for the exact gradient per color.
- Color modifiers (fill / edge):
  - `.pink`  `#f3b5b5` / `#d59999`
  - `.blue`  `#b8c8e0` / `#9bacc7`
  - `.peach` `#f4c896` / `#d6ab7c`
  - `.mint`  `#c6dcc2` / `#a8bfa5`
  - hero yellow `#f3d96b` / `#d8bd55` (applied inline on the hero)
- **Tag** (`.tag`): JetBrains Mono, 11px, letter-spacing .1em, uppercase, color `#5a4a36`, margin-bottom 8px.

### Hero note (center)
- Yellow sticky, `padding:38px 46px; width:600px;` rotated −1.2°, heavier shadow
  (`…0 32px 64px -16px rgba(70,50,20,.32)`).
- Two strips of **washi tape** (`.tape`) pinned at the top (`top:-11px`, one left rotated −7°, one
  right rotated 6°). Tape: 72×18px, `rgba(245,230,180,.78)`, 1px warm border, faint shadow.
- Eyebrow tag: "This week · top of mind".
- **Headline** (`h1`): Newsreader 700, **62px**, line-height .98, letter-spacing −0.025em, color `#2a1f14`.
  Two lines: `Tasks you keep` + accent line `actually doing.` in **italic, weight 500, color #3b2a16**.
- **Sub-copy** (`p`): Newsreader 17px, line-height 1.45, color `#3b2e1c`, max-width 480px:
  *"Capture what's on your plate. Talk through a realistic week with the assistant. Agreed tasks land
  on your calendar as time blocks."*
- **CTA row**: primary button + ghost text link (see CTA section).
- 🍍 placeholder pinned at `right:-54px; bottom:-40px; rotate(5deg)` (see Assets).

### Satellite notes — exact copy
- **Pink — "The problem"**: *Tasks get written down — then quietly buried.*
- **Blue — "The method"**: *Time-blocking — but you don't do the planning.* + meta: *The assistant proposes; you push back.*
- **Peach — "The payoff"**: *A week you can actually commit to.* + meta: *Not another list to ignore.* (+ a tape strip)
- **Mint — "Yours alone"**: *No ads. No trackers. Encrypted at rest. ZDR AI.* (+ a tape strip)
- Note headline (`.satH`): Newsreader 700, 17px (mint uses 16px), line-height 1.22, letter-spacing −0.01em.
- Meta line: italic, 13px, color `#5a4a36`.

### Primary CTA — "Get started" button (`.btn.btn-primary`)
- `background:#2a1f14; color:#fdf7e2; padding:15px 26px; border-radius:999px;` Newsreader 17px weight 600.
- Shadow `0 5px 16px -5px rgba(0,0,0,.4)`. Hover: `translateY(-1px)` (150ms ease).
- Label: **"Get started — it's free"**. Opens the login modal.
- Beside it, a ghost link: italic Newsreader 15px, color `#5a4a36`, underline w/ 4px offset:
  **"or play in a sandbox →"** (this triggers the no-signup sandbox account — see State).

### Login modal
Opened by the Get started button (and by the nav "Log in" link). Dismiss on overlay click, the ✕
button, or **Escape** key.
- **Overlay**: `position:absolute; inset:0; z-index:50; background:rgba(40,28,12,.42); backdrop-filter:blur(3px);`
  grid-centered. Fade-in animation `cbfade` 180ms.
- **Modal card**: width 420px, `background:#fbf6e3; border-radius:16px; padding:34px 34px 26px;`
  1px warm border, big shadow `0 30px 70px -20px rgba(30,20,8,.55)`, rotated −0.4° (playful nod).
- **Close button**: top-right, 30px circle, `background:rgba(120,90,40,.1)`, mono ✕.
- **Tag**: "Welcome in" (mono, uppercase, .16em).
- **Heading** (`h2`): Newsreader 700, 27px, line-height 1.1: *"Pick how you'd like to continue"*.
- **Sub**: Newsreader 15px color `#5a4a36`: *"One tap. We'll create your board if it's your first time."*
- **Channel buttons** (stacked, `gap:10px`, full-width) — **data-driven, render in this order**:
  - **Telegram**: `background:#2aabee; color:#fff;` shadow `0 6px 16px -6px rgba(42,171,238,.55)`. Telegram glyph + "Continue with Telegram".
  - **Google**: `background:#fff; color:#2a1f14; border:1.5px solid rgba(60,40,15,.2)`. Multi-color Google "G" + "Continue with Google".
  - **Email**: `background:transparent; color:#2a1f14; border:1.5px solid #2a1f14`. Envelope icon + "Continue with email".
- **"More ways to sign in are on the way"** — centered mono caption below buttons.
- **Divider**: hairline + centered mono label "just looking?".
- **Sandbox option**: full-width dashed-border card, two lines — *"Open a sandbox account →"* +
  italic sub *"A fully-featured demo board. No sign-up."*
- **Fine print**: Newsreader 12px, centered: *"By continuing you agree to our Terms and Privacy Policy.
  No ads, no trackers — ever."*

### Icons (inline SVG, in `welcome-corkboard.jsx`)
- `TgIcon` — Telegram paper-plane in a circle (uses `currentColor`).
- `GIcon` — Google "G", four brand colors (`#4285F4 #34A853 #FBBC05 #EA4335`).
- `MailIcon` — stroked envelope (`currentColor`, 1.8 stroke).
Swap these for the codebase's icon set if one exists; keep the Google brand colors.

---

## Interactions & Behavior
- **Get started** button → opens login modal (`modalOpen` state true).
- **Nav "Log in"** → opens the same modal.
- **Modal dismiss**: overlay click, ✕ click, or `Escape` keydown (listener added/removed on open).
- **Channel buttons** → kick off the respective OAuth/login flow (Telegram widget, Google OAuth,
  email magic-link or password — per your auth backend). **Not wired in the prototype.**
- **"Play in a sandbox" / "Open a sandbox account"** → log the user straight into a pre-seeded
  demo account with no registration (this behavior already exists in the current app — reuse it).
- **Hover**: all `.btn` rise 1px (150ms ease). Ghost links underline at 4px offset.
- **Animations**: modal overlay fades in (`@keyframes cbfade`, opacity 0→1, 180ms ease). No looping
  or decorative animation elsewhere.

---

## Responsive behavior
The prototype uses a **fixed 1320×860 stage scaled with `transform: scale()`** to fit any viewport
(letterboxed, never cropped) — see the `fitStage()` script in `Welcome — Corkboard.html`
(`scale = min((vw−32)/1320, (vh−32)/860, 1)`).

**Recommendation for production:** this scale-to-fit approach is fine for a quick mock but is not
ideal for a real responsive page (it scales text down on small screens and doesn't reflow). Prefer
a **fluid/responsive rebuild**:
- Desktop (≥~1100px): the pinboard composition as specified.
- Tablet/mobile: collapse to a single column — hero note centered and full-width-ish, satellite
  notes stacked below it (or reduced to 2), nav condensed. The hero CTA + modal stay identical.
- Keep the absolute-positioned satellites only on wide viewports; switch to normal flow below a breakpoint.

A mobile layout was **not** designed in this round — flag if you want one before building.

---

## State Management
- `modalOpen: boolean` — controls login modal visibility.
- Auth state — out of scope for the prototype; wire to the app's existing auth/session.
- The (now-removed) Tweaks state (`useTweaks`) is **prototype-only** — do not port.

---

## Design Tokens

### Colors
| Token | Hex | Use |
|---|---|---|
| ink | `#2a1f14` | primary text, primary button bg, logo bg `#1c140a` |
| ink-soft | `#5a4a36` | secondary text, tags, ghost links |
| ink-mute | `#8a7a5d` | captions, footer, dividers |
| cream / paper | `#fbf6e3` | modal bg; page uses radial cream gradient (`#efe6cd→#e6dcbd→#ddcfa6`) |
| rule | `#d6cbb0` | hairline dividers |
| sticky yellow | `#f3d96b` / edge `#d8bd55` | hero note |
| sticky pink | `#f3b5b5` / `#d59999` | problem note |
| sticky blue | `#b8c8e0` / `#9bacc7` | method note |
| sticky peach | `#f4c896` / `#d6ab7c` | payoff note |
| sticky mint | `#c6dcc2` / `#a8bfa5` | privacy note |
| telegram | `#2aabee` | Telegram button |
| google brand | `#4285F4 #34A853 #FBBC05 #EA4335` | Google "G" |

### Typography
- **Display / body**: **Newsreader** (Google Fonts), an optical-size serif. Weights used: 400, 500,
  600, 700; italics at 400/500/600. Headline 62px/700, modal h2 27px/700, body 15–17px/400,
  italic accents 500.
- **Mono / labels**: **JetBrains Mono** (Google Fonts), 400–600. Used for eyebrows, tags, nav links,
  footer, captions — typically 10–11px, letter-spacing .1–.18em, **uppercase**.
- Map both to the codebase's font setup; if substituting, keep a serif-display + mono-label pairing.

### Spacing / radii / shadows
- Note radius: `2px 2px 14px 2px` (hero `2px 2px 18px 2px`) — asymmetry is intentional (peeled corner).
- Button radius: `999px` (pill). Modal radius: `16px`. Sandbox card: `12px`.
- Nav/footer padding: `~24–26px × 44px`. Hero note padding: `38px 46px`. Satellite padding: `14px 16px 16px`.
- Shadow scale (soft, warm-tinted) — see `.sticky`, hero, `.btn-primary`, modal values above.
- Rotations: hero −1.2°; satellites −5°/3.5°/4.5°/−4°; modal −0.4°; tape ±5–7°.

---

## Assets
All imagery in the prototype is **placeholder** — replace with real assets:
- **🍍 Pineapple pet** — currently a dashed `.photo-ph` box with an emoji. The brand has a pineapple
  mascot; drop in the real illustration/photo here (small, ~74×92px, pinned to the hero's
  bottom-right corner, slight 5° rotation). This is the "subtle nod" to the mascot.
- Consider whether the satellite notes should carry small photos (the brand leans toward real
  photographs of sticky notes / desk). Optional.
- Fonts are loaded from Google Fonts in the prototype; self-host or use the app's font pipeline.
- Icons are inline SVG (see above) — swap for the app's icon library if it has equivalents.

---

## Open items to confirm before/while building
1. **Mobile layout** — not yet designed. Recommend a single-column stack (see Responsive behavior).
2. **Real pineapple asset** + any note photography.
3. **Auth wiring** — Telegram widget vs. bot deep-link, Google OAuth client, email flow (magic link
   vs. password). The design is channel-agnostic; the list is data-driven so providers can be added.
4. Whether to keep the **fixed-stage scaling** (quick) or do a **fluid rebuild** (recommended).
