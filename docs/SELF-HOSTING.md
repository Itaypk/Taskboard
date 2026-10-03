# Self-hosting

Run your own instance of Backlog.fyi with Docker Compose: the app, PostgreSQL, and optionally Caddy
for automatic HTTPS. Everything lives in [`deploy/`](../deploy). The full list of settings is
[`CONFIGURATION.md`](CONFIGURATION.md); this guide covers what a typical instance needs.

## What you need

- A Linux host with Docker and the Compose plugin (`docker compose version`). Images are published
  for `amd64` and `arm64`.
- About 1 GB of RAM for the app and the database.
- For HTTPS through the bundled Caddy: a domain name pointing at the host, and ports 80 and 443
  reachable from the internet (Let's Encrypt validates through them).

Optional integrations, all off unless configured:

| Feature | Needs |
|---|---|
| Weekly planning assistant, AI capture | An [OpenRouter](https://openrouter.ai) API key |
| Magic-link sign-in, calendar invitations | SMTP accounts |
| Telegram sign-in and bot | A bot from [@BotFather](https://t.me/BotFather) |

Without any of them you still get the backlog board, signing in with a username and password.

## Quick start

```bash
git clone https://github.com/Itaypk/Taskboard.git
cd Taskboard/deploy
cp .env.example .env
```

Edit `.env` and fill in at least:

- `TASKER_APP_BASE_URL`: the public URL, e.g. `https://tasks.example.com` (no trailing slash).
- `TASKBOARD_DOMAIN`: its host name, e.g. `tasks.example.com` (for Caddy's certificate).
- `POSTGRES_PASSWORD`: any long random string.
- `TASKER_DATA_KEK`: the key that encrypts task contents and other personal data. Generate it with
  `openssl rand -base64 32`.

> **Back up `TASKER_DATA_KEK` somewhere safe, separately from your database backups.** Without it
> the encrypted data in the database can't be read by anyone, including you. With it *and* a
> database dump, all of it can.

Values in `.env` are taken literally except for `$`, which Compose treats as a variable reference;
avoid `$` in passwords, or wrap the value in single quotes.

Create a user (next section), then start everything:

```bash
docker compose --profile proxy up -d
docker compose logs -f app
```

Once the app has started, the log shows a line like
`Instance ready at https://tasks.example.com: sign-in=[password (1 user(s))], registration=closed, …`
summarizing what's enabled. Open the URL and sign in.

## Signing in

The example `.env` sets up a private instance: registration closed, the no-sign-up demo off, and
sign-in with a username and password for the users you list.

Users go in `deploy/config/users`, one `username:bcrypt-hash` per line (start from
`config/users.example`). Create a line with:

```bash
docker run --rm httpd:2-alpine htpasswd -nbBC 12 alice 'a long password'
```

Restart the app after editing the file (`docker compose restart app`). There's no sign-up or
password reset for these users: you manage them in the file. Changing a password doesn't end
sessions that are already signed in; use **Settings → Sign out other sessions** for that.

Other ways in, which can be combined:

- **Email magic links** (`TASKER_EMAIL_*`): anyone with an existing account, or anyone at all if
  `TASKER_REGISTRATION=open`, can sign in by email.
- **Telegram** (`TASKER_TELEGRAM_CLIENT_ID` / `TASKER_TELEGRAM_CLIENT_SECRET`): set your instance's
  domain under BotFather → Bot Settings → Web Login. The bot itself (`TASKER_TELEGRAM_BOT_TOKEN`)
  runs the planning conversation and quick-add.
- **Open registration** (`TASKER_REGISTRATION=open`, optionally `TASKER_DEMO_ENABLED=true`): for a
  public instance. Mind the AI costs: every account can use the assistant within its token budget.

## The reverse proxy

The app always sits behind a reverse proxy: its session cookie only travels over HTTPS, it takes
the client address for rate limiting from `X-Forwarded-For`, and it leaves security headers and
caching to the proxy. That's why Compose publishes its port on `127.0.0.1` only.

**Bundled Caddy** (`--profile proxy`): gets and renews a certificate for `TASKBOARD_DOMAIN`, and sets
security headers (including a Content-Security-Policy) and cache headers. Its configuration is
[`deploy/Caddyfile`](../deploy/Caddyfile); adjust it there.

**Your own proxy** (Traefik, nginx, another Caddy, …): start without the profile
(`docker compose up -d`) and point the proxy at `127.0.0.1:8080` (`APP_PORT` changes the port).
Make sure it:

- terminates HTTPS;
- **replaces** `X-Forwarded-For` with the client address, or appends it as the last entry. The app
  uses the last entry; a proxy that passes a client-supplied header through unchanged lets anyone
  dodge the rate limits on sign-in;
- sets the security headers and the cache policy: `Cache-Control: no-cache` for `/` and
  `/index.html`, and long-lived `immutable` caching for `/assets/*`. The Caddyfile is a working
  example of both.

If you'd rather put your own proxy on the Compose network instead of the host, remove the `ports:`
section from the `app` service and proxy to `app:8080`.

## Branding

A public instance must use its own name, logo and icons rather than Backlog.fyi's
(see [`TRADEMARKS.md`](../TRADEMARKS.md)); for a private one it's up to you.

- `TASKER_APP_NAME`, `TASKER_SUPPORT_EMAIL`, `TASKER_ABUSE_EMAIL` in `.env` change the name and the
  contact addresses everywhere: page titles and link previews, emails, the bot, the assistant.
- Icons: put replacement files (`favicon.ico`, `favicon-16x16.png`, `favicon-32x32.png`, `favicon-48x48.png`,
  `apple-touch-icon.png`, `icon-192x192.png`, `icon-512x512.png`, `og-image.png`) in a directory,
  mount it into the `app` service and list it ahead of the bundled files:

  ```yaml
  # deploy/compose.yaml, under services.app
  volumes:
    - ./config:/config:ro
    - ./branding:/branding:ro
  environment:
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS: file:/branding/,classpath:/static/
  ```

## Backups

Two things to keep, stored apart from each other:

1. **The database.** For example, nightly:

   ```bash
   docker compose exec -T db pg_dump -U taskboard -d taskboard --format=custom > taskboard-$(date +%F).dump
   ```

   Restore into an empty database with `pg_restore`.
2. **`TASKER_DATA_KEK`**, once. It never changes, but without it the dumps are useless.

## Upgrading

Every build of the main branch is published as `ghcr.io/itaypk/taskboard:build-<number>`, and
`latest` follows the newest one. For predictable upgrades, pin a version in `.env`:

```bash
TASKBOARD_VERSION=build-123
```

To upgrade: back up the database, change the version, then

```bash
docker compose pull app && docker compose up -d app
```

Database migrations run on startup and only go forward. Going back to an older version after
upgrading isn't supported, so restore the backup if you need to roll back.

## Troubleshooting

- **The app restarts in a loop.** `docker compose logs app` shows why; startup fails on purpose
  when `TASKER_APP_BASE_URL` or `TASKER_DATA_KEK` is missing or invalid, or when
  `TASKER_LOCAL_USERS_FILE` points at a file that doesn't exist.
- **Sign-in seems to work but you land back on the login page.** The session cookie only travels
  over HTTPS. Use the HTTPS URL through the proxy, not `http://<host>:8080`.
- **"No sign-in method is available"** in the log: no users file, email or Telegram is configured,
  and the demo is off.
- **Metrics:** `/actuator/prometheus` stays closed unless you set `TASKER_PROMETHEUS_USERNAME` and
  `TASKER_PROMETHEUS_PASSWORD` (HTTP Basic auth). `/actuator/health` is always open.

## Building the image yourself

From the repository root, with JDK 25 and Node.js 22:

```bash
./gradlew release
docker build -t taskboard .
```

Then set `image: taskboard` for the `app` service in `deploy/compose.yaml`.

## License

Backlog.fyi is licensed under the [AGPL-3.0](../LICENSE). If you modify it and let others use
your instance over a network, you must offer them the source code of your modified version.
