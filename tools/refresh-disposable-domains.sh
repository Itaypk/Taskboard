#!/usr/bin/env bash
#
# Refreshes the vendored disposable-email-domain list used by EmailDomainBlocklistService.
#
# Source: https://github.com/disposable/disposable-email-domains (MIT) — the published artifact
# of the `disposable/disposable` generator. We vendor a gzipped snapshot rather than fetching at
# runtime so the app boots with no network dependency, tests run offline, and a refresh shows up
# as a reviewable commit.
#
# The sanity checks below are the point of this script: a poisoned or truncated upstream would
# otherwise lock every real user out of magic-link login. Run it manually and commit the result.
#
# Usage: tools/refresh-disposable-domains.sh
set -euo pipefail

SOURCE_URL="https://raw.githubusercontent.com/disposable/disposable-email-domains/master/domains.txt"
TARGET="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/email/disposable-domains.txt.gz"

# Providers our real users actually sign up with. If any of these appear upstream, the list is
# poisoned or its semantics changed — abort rather than ship a login outage.
MUST_NOT_CONTAIN=(gmail.com googlemail.com outlook.com hotmail.com live.com yahoo.com
                  proton.me protonmail.com pm.me icloud.com me.com aol.com gmx.com fastmail.com)

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo "Fetching $SOURCE_URL ..."
curl -fsSL "$SOURCE_URL" -o "$tmp"

new_count="$(grep -c . "$tmp" || true)"
if [ "$new_count" -lt 1000 ]; then
  echo "ABORT: upstream returned only $new_count domains — that is not a real list." >&2
  exit 1
fi

if [ -f "$TARGET" ]; then
  old_count="$(gzip -cd "$TARGET" | grep -c . || true)"
  # A legitimate refresh moves by a few percent. A 25% swing in either direction means something
  # structural changed upstream; look at it by hand before accepting.
  lo=$(( old_count * 75 / 100 ))
  hi=$(( old_count * 125 / 100 ))
  if [ "$new_count" -lt "$lo" ] || [ "$new_count" -gt "$hi" ]; then
    echo "ABORT: domain count moved $old_count -> $new_count, outside the ±25% sanity band." >&2
    echo "Inspect the upstream diff, then re-run with the band widened if the change is real." >&2
    exit 1
  fi
fi

for domain in "${MUST_NOT_CONTAIN[@]}"; do
  if grep -qxF "$domain" "$tmp"; then
    echo "ABORT: upstream lists '$domain', a mainstream provider. Refusing to ship it." >&2
    exit 1
  fi
done

gzip -9 -c "$tmp" > "$TARGET"
echo "Wrote $TARGET ($new_count domains, $(wc -c < "$TARGET") bytes gzipped)."
echo "Review with: git diff --stat && gzip -cd '$TARGET' | head"
