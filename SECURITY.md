# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Report it privately instead:

1. **Preferred:** GitHub's private vulnerability reporting. Go to the repository's **Security** tab and choose
   **Report a vulnerability** ([direct link](https://github.com/Itaypk/Taskboard/security/advisories/new)).
2. **Alternatively,** email [hello@backlog.fyi](mailto:hello@backlog.fyi).

Please include what's affected (an endpoint, a file, a flow), steps to reproduce or a proof of concept, and
the impact as you see it.

## What to expect

This is a solo, non-commercial project, so handling is best-effort:

- You'll get an acknowledgement, usually within a week.
- Once the issue is confirmed, it's fixed in the code here and deployed to the hosted instance. Severe
  issues come first.
- With your agreement, you'll be credited in the published advisory.

There is no bug bounty.

## Scope

In scope:

- The code in this repository (backend, web app, Telegram bot, external API, Docker image).
- The hosted instance at [backlog.fyi](https://backlog.fyi), including its server configuration (for example
  security headers and TLS).

Out of scope:

- Instances run by other people. Report configuration issues to their operator; report code issues here.
- Denial-of-service and volumetric testing, spam, and social engineering.
- Findings with no demonstrable security impact (for example missing best-practice headers on their own,
  or version disclosure).

## Testing guidelines

When testing against the hosted instance, use only accounts you own (the demo login is fine), don't access
or modify other users' data, and keep automated scanning gentle. If you come across someone else's data by
accident, stop and include that in your report. Good-faith research within these limits is welcome, and it
won't be pursued legally.

## Supported versions

Only the latest release (the current `main`) receives fixes. Self-hosters should stay on a recent
`ghcr.io/itaypk/taskboard` image.
