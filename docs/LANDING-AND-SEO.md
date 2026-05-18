# Landing Page & SEO Checklist

Living document tracking improvements to the logged-out experience and discoverability of
`backlog.fyi`. Hobby / non-commercial scope — items marked _(skip)_ are noted and intentionally
deferred.

Today the only thing an unfamiliar visitor (or crawler, or LLM agent) sees is the login card:
a one-line tagline, a Telegram widget, a demo button, and ToS/PP modal links. That's the
baseline we're improving from.

## Newcomer experience

- [x] **Hero copy** — tagline kept, plus a one-paragraph elaboration on the live login page.
- [x] **"How it works"** — three steps surfaced on the login page.
- [ ] **Screenshot of the post-it board** — biggest single legibility win for both humans and
      link-preview bots. Doubles as the OG image.
- [ ] **"Why Telegram?"** — one-liner; new visitors will be confused that login goes through a
      chat app.
- [x] **Promote the demo button** — now the primary CTA on the login page, above the Telegram
      widget.
- [x] **Honest status note** — surfaced inline on the login page.
- [ ] **Footer**: GitHub link, contact / about line, ToS, Privacy. _(ToS/Privacy done; GitHub +
      contact still missing.)_
- [ ] **FAQ** _(optional)_: pricing (free, hobby), data handling, why Google Calendar, account
      deletion.

## SEO basics

Already in place: `<title>`, `<meta description>`, OG + Twitter card tags, `lang="en"`,
`favicon.svg`, `robots.txt`, `sitemap.xml`.

- [ ] **OG/Twitter image** — `og:image` + `twitter:image` are missing, so link previews look
      empty. Use a 1200×630 board screenshot; upgrade `twitter:card` to `summary_large_image`.
- [x] **Canonical link** — `<link rel="canonical" href="https://backlog.fyi/" />`.
- [x] **Sharper title + description** — now mentions Telegram + Google Calendar.
- [x] **JSON-LD `SoftwareApplication` block** in `index.html`.
- [x] **SPA crawlability** — `<div id="prerendered-landing" hidden>` block in `index.html`
      contains hero, what-it-does, how-it-works, and status. Invisible to browsers (the SPA
      replaces `#root` above it), visible to crawlers and parsers that don't render CSS.
- [ ] **Apple touch icon + 512×512 PNG** for share sheets on iOS/Android.
- [x] **Make ToS and Privacy real routes** (`/terms`, `/privacy`) — added `react-router-dom`,
      `TermsPage` / `PrivacyPage` components, and `SpaForwardController` so direct loads of those
      paths return `index.html`. Modal removed in favour of route navigation.
- [x] **Update `sitemap.xml`** to include `/terms` and `/privacy`.

## Accessibility & polish

- [ ] Visible focus rings on the login buttons (currently inline-styled).
- [ ] Verify WCAG AA contrast for `#888` / `#555` body text on the paper background.
- [ ] `<main>` / `<header>` / `<footer>` landmarks on the public page.
- [ ] `alt` text on the screenshot.

## AI-agent friendliness

- [ ] **`/about` page** with plain prose describing the product loop — what LLM crawlers will
      quote.
- [ ] **`llms.txt`** at the root _(optional)_ — short markdown summary + key URLs.

## Suggested sequencing

The cheapest, highest-leverage batch first:

1. ~~Sharpen `<title>` and `<meta description>`; add canonical.~~ ✅
2. ~~Render marketing copy (hero + how-it-works + screenshot placeholder) as static HTML in
   `index.html` so crawlers see it.~~ ✅
3. Add a real screenshot and wire it as `og:image` / `twitter:image`.
4. ~~Promote the demo button and add the status note in the live login card.~~ ✅
5. ~~Promote ToS/Privacy to real routes; update sitemap.~~ ✅
6. JSON-LD ✅, `llms.txt`, `/about`.
