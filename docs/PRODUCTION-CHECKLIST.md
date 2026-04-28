# Production Deployment Checklist

Tracking what's left before publicizing the deployment at `backlog.fyi`. Items that live in the
infra repo are listed for completeness but not tracked here.

## Already in place
- [x] Metrics & observation (infra repo)
- [x] Log collection (infra repo)
- [x] Database backups (infra repo)
- [x] Security headers via NGINX (infra repo) — everything except CSP, which belongs at the app layer
- [x] `robots.txt` and `sitemap.xml` served from `src/main/resources/static/`

## Legal & content
- [x] **Terms of Service** — link/modal on login page with placeholder verbiage; replace with final copy when ready
- [x] **Privacy policy** — same treatment; placeholder covers Telegram data and session cookie
- [ ] **Support / abuse contact address** referenced from ToS + PP
- [ ] **Landing page** for logged-out visitors explaining what `backlog.fyi` is
- [x] **Rebranding** — "Backlog.fyi" in browser title, login heading, and board logo tape

## Security (app layer)
- [x] **Content-Security-Policy** header in `SecurityConfiguration` — allows `telegram.org` (widget script), `oauth.telegram.org` (widget iframe), `fonts.googleapis.com` + `fonts.gstatic.com` (Google Fonts), `data:` (inline SVG textures); `frame-ancestors 'none'`. `style-src` keeps `'unsafe-inline'` because of React inline styles — revisit if we move to nonces/classes.
- [ ] **Smoke test confirming `dev-login` returns 404/401 in prod** — currently gated by `@Profile("dev")`, but we should assert it from outside, not just trust the annotation.
- [ ] **Dependency / CVE scanning** — Dependabot for Gradle + npm, optionally Trivy on the container image
- [ ] **Secret rotation plan** documented: `TASKER_TELEGRAM_BOT_TOKEN`, `TASKER_PROMETHEUS_*`, DB credentials
- [ ] **Edge protection** (Cloudflare or equivalent) in front of the host for DDoS + bot filtering

## Abuse prevention
- [x] **Per-user rate limits** — sliding-window in-memory limiter (300 req/min per user on all API endpoints; 5 req/hour per IP on demo-login). `RateLimiter` interface ready to swap for a Redis-backed implementation when running multiple replicas.
- [ ] **Demo account cap** (total simultaneous demo accounts)
- [ ] **Demo account TTL / sweeper job** so the cap doesn't block onboarding indefinitely

## User data (GDPR-style)
- [x] **Account deletion** — `DELETE /api/v1/account` (cascades tasks, tags, categories, settings, sessions); Settings modal has two-step confirmation UI + navigates to login on success
- [x] **Data export** — `GET /api/v1/account/export` returns JSON download (`Content-Disposition: attachment`); "Export my data" button in Settings modal

## Operations
- [ ] **Error tracking** (Sentry / GlitchTip) — distinct from log collection: dedup, stacktraces, release tagging
- [ ] **Backup *restore* drill** — periodic exercise that proves backups are usable, not just present
- [ ] **Graceful shutdown + readiness probe** wired into the deploy pipeline so rollouts don't drop in-flight requests

## SPA polish
- [ ] **404 / error route** in the React shell — currently an unknown path renders the empty app
- [x] **OG / Twitter meta tags** on the landing/login pages so shared links render properly
