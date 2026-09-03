#!/usr/bin/env bash
# Regenerates tasker-frontend/src/fonts.css and tasker-frontend/src/assets/fonts/ — the vendored
# @font-face declarations *and* woff2 files for the web fonts the UI uses.
#
# Why vendored: a <link> to fonts.googleapis.com is render-blocking on a third origin, so nothing
# paints until that round trip completes — shipping the declarations in our own stylesheet removes
# that request. The woff2 files themselves are vendored too (not just the CSS): every request to
# fonts.gstatic.com is a live connection to Google from every visitor's browser, on every page load,
# for a domain unrelated to Backlog's own privacy posture; self-hosting removes that beacon and the
# DNS+TLS round trip to a third origin. font-display: swap keeps them non-blocking for paint either
# way.
#
# The gstatic URLs are versioned and immutable, which is the trade-off: fonts stay frozen at the
# version captured here until this script is re-run. Do that when you want font updates or when
# adding a family/weight — edit FAMILIES below, run the script, and commit the result (css + fonts/).
set -euo pipefail

FAMILIES="family=Fraunces:ital,wght@0,500;0,600;0,700;1,400;1,600&family=IBM+Plex+Mono:wght@500;600&family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap"
FRONTEND="$(dirname "$0")/../tasker-frontend"
OUT="$FRONTEND/src/fonts.css"
FONTS_DIR="$FRONTEND/src/assets/fonts"

# A modern-browser UA is required: Google serves woff2 only to clients it recognises, and falls
# back to the much larger (and worse-supported) ttf otherwise.
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

tmp_css="$(mktemp)"
trap 'rm -f "$tmp_css"' EXIT

curl -sSf -A "$UA" "https://fonts.googleapis.com/css2?$FAMILIES" > "$tmp_css"

# Re-created from scratch each run: filenames are gstatic's own content hashes, so a stale file
# left behind by a since-changed font version would just be dead weight, never a broken reference.
rm -rf "$FONTS_DIR"
mkdir -p "$FONTS_DIR"

# Download every referenced woff2 and rewrite the CSS to point at the local copy (a path relative
# to fonts.css, which Vite resolves/hashes like any other CSS url() asset reference).
mapfile -t urls < <(grep -oP 'https://fonts\.gstatic\.com/\S+?\.woff2' "$tmp_css" | sort -u)
for url in "${urls[@]}"; do
  filename="$(basename "$url")"
  curl -sSf -A "$UA" -o "$FONTS_DIR/$filename" "$url"
  # `|` delimiter: the URL itself contains `/`.
  sed -i "s|$url|./assets/fonts/$filename|g" "$tmp_css"
done

{
  echo "/*"
  echo " * Vendored web-font faces + files. GENERATED — do not edit by hand."
  echo " * Regenerate with tools/refresh-google-fonts.sh (see that script for why these are vendored)."
  echo " * Source: https://fonts.googleapis.com/css2?$FAMILIES"
  echo " */"
  cat "$tmp_css"
} > "$OUT"

trap - EXIT
echo "Wrote $OUT and ${#urls[@]} font files to $FONTS_DIR"
