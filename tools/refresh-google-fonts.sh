#!/usr/bin/env bash
# Regenerates tasker-frontend/src/fonts.css — the vendored @font-face declarations for the web
# fonts the UI uses.
#
# Why vendored: a <link> to fonts.googleapis.com is render-blocking on a third origin, so nothing
# paints until that round trip completes. Shipping the declarations in our own stylesheet removes
# that request; the woff2 files themselves still come from gstatic (font-display: swap, so they
# never block paint either).
#
# The gstatic URLs are versioned and immutable, which is the trade-off: fonts stay frozen at the
# version captured here until this script is re-run. Do that when you want font updates or when
# adding a family/weight — edit FAMILIES below, run the script, and commit the result.
set -euo pipefail

FAMILIES="family=Fraunces:ital,wght@0,500;0,600;0,700;1,400;1,600&family=IBM+Plex+Mono:wght@500;600&family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap"
OUT="$(dirname "$0")/../tasker-frontend/src/fonts.css"

# A modern-browser UA is required: Google serves woff2 only to clients it recognises, and falls
# back to the much larger (and worse-supported) ttf otherwise.
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

{
  echo "/*"
  echo " * Vendored web-font faces. GENERATED — do not edit by hand."
  echo " * Regenerate with tools/refresh-google-fonts.sh (see that script for why these are vendored)."
  echo " * Source: https://fonts.googleapis.com/css2?$FAMILIES"
  echo " */"
  curl -sSf -A "$UA" "https://fonts.googleapis.com/css2?$FAMILIES"
} > "$tmp"

mv "$tmp" "$OUT"
trap - EXIT
echo "Wrote $OUT"
