# Git history secret scan (pre-open-sourcing)

Status: **done, clean**. Performed 2026-07-27 ahead of a possible switch from private to public
repo visibility, as a prerequisite for open-sourcing the application under AGPL-3.0.

## Why

Before making the repo public, its entire git history — not just the current tree — needs to be
checked for secrets: API keys, tokens, credentials, private keys, etc. Anything ever committed,
even if later removed, is permanently visible in a public repo's history.

## Methodology

1. **Tool**: [gitleaks](https://github.com/gitleaks/gitleaks) (via its original module path,
   `github.com/zricethezav/gitleaks/v8`), built from source with `go install` since no binary was
   preinstalled. Chosen over a manual grep pass because it combines a curated regex ruleset
   (cloud provider keys, private key headers, common token formats, etc.) with Shannon-entropy
   detection for generic high-entropy strings, and walks `git log -p` so it inspects every diff
   ever introduced, not just the current file contents.
2. **Full history, not just the checked-out branch**: the session's clone was shallow and
   single-branch by default (98 commits visible). Ran `git fetch --unshallow` plus
   `git fetch origin '+refs/heads/*:refs/remotes/origin/*' '+refs/pull/*/head:refs/remotes/origin/pr/*'`
   to pull every branch and PR ref, taking the reachable commit count from 98 to 589 (~25
   feature/claude branches, dependabot branches, and 91 PR refs).
3. **Scan**: `gitleaks git --log-opts="--all" --report-format=json` over all refs.
4. **Manual supplementary checks**, covering classes of leak a regex/entropy scanner can miss:
   - Filenames ever committed matching sensitive patterns (`.env`, `.pem`, `.key`, `credentials`,
     `application-prod*`, etc.) via `git log --all --diff-filter=A --name-only`.
   - Every historical version of each matched config file (`application-prod.yaml`,
     `tasker-frontend/.npmrc`), diffed for hardcoded values vs. env-var placeholders.
   - `deploy.sh` history, and whether the `constants.sh` file it sources (holding the real
     deploy host/user/key path) was ever committed.
   - Files ever deleted that matched deploy/docker/env/ansible naming, in case a secret was
     committed then scrubbed from the tip but left in history.
   - `git fsck --full --unreachable --no-reflogs` for dangling objects (orphaned blobs from
     amended/rebased-away commits that might still hold old secrets).
   - Largest blobs in history (`git rev-list --objects --all` + `cat-file --batch-check`), to
     catch binary secrets (keystores, DB dumps) that text-oriented scanners skip.
   - A manual grep across every historical diff for common real-world token shapes (Telegram bot
     tokens, `sk-`/`AIza`/`AKIA`/`ghp_`/`xox` prefixes, PEM private-key headers) as a second pass
     beyond gitleaks' default ruleset.

## Findings

**No real secrets found anywhere in the git history.**

- gitleaks reported exactly one finding, a false positive: the string `outcome=failure` in
  `CLAUDE.md` (Prometheus metric label documentation) tripped the generic high-entropy heuristic.
- `application-prod.yaml`: checked at every commit that ever touched it — always uses
  `${TASKER_DB_PASSWORD}`-style env var placeholders, never a literal credential.
- `tasker-frontend/.npmrc`: only ever contained `save-exact=true`.
- `deploy.sh`: parameterizes the real host/user/key path via `constants.sh`, which is
  `.gitignore`'d (`/constants.sh`) and was never committed at any point in history.
- No deleted `.env`/docker/ansible files exist anywhere in history.
- No dangling/unreachable objects.
- Largest historical blobs are mascot PNGs and `package-lock.json` — no stray keystores or DB
  dumps.
- The checked-in dev/test KEK placeholder (`application.yaml`'s
  `TASKER_DATA_KEK:AAECAwQFBgcICQoLDA0O...` default) is sequential filler bytes, documented in
  the surrounding config as dev-only and never to be reused for a real key — not a leaked
  production secret.
- The one Telegram-bot-token-shaped string found in history (`1234567890:AAAA...`) is an
  obviously fake placeholder in a test env-example file, not a real token.

## Conclusion

Nothing in the git history should block switching this repo to public. This scan should be
re-run (or at minimum, gitleaks re-run over `--all` history) if a real secret is ever suspected
to have been committed before that switch happens.
