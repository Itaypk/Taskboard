# tools

Ad-hoc scripts for preparing assets. Not part of the build — run them by hand when
adding or reworking an asset, then commit the result.

Requires Python 3 with `Pillow` and `numpy` (and `rembg` only for background removal).

## Refreshing the disposable-email-domain list

`refresh-disposable-domains.sh` re-fetches
[disposable/disposable-email-domains](https://github.com/disposable/disposable-email-domains) (MIT)
and rewrites `src/main/resources/email/disposable-domains.txt.gz`, the ~75k-domain blocklist
`EmailDomainBlocklistService` loads at startup. It needs only `curl` and `gzip`:

```
tools/refresh-disposable-domains.sh
git diff --stat   # then commit the .gz
```

The script aborts rather than writing if the upstream list is implausibly small, moves by more than
±25%, or contains a mainstream provider (gmail, outlook, proton, …). Those checks are the reason it
exists: a poisoned or truncated upstream would lock every real user out of magic-link login. Run it
occasionally — the list is not on the build path, so a stale snapshot only means newer throwaway
domains slip through, never an outage.

## Refreshing the vendored web fonts

`refresh-google-fonts.sh` re-fetches the `@font-face` declarations *and* the `woff2` files for the
three UI families, rewriting `tasker-frontend/src/fonts.css` (which `src/index.css` imports) and
`tasker-frontend/src/assets/fonts/`. Both are vendored rather than pulled from
`fonts.googleapis.com`/`fonts.gstatic.com` because that's a render-blocking third origin — nothing
paints until a DNS + TLS + request round trip to Google completes, which costs a real slice of
First Contentful Paint on mobile — and because self-hosting removes a per-visitor request to Google
on every page load. Every face is still `font-display: swap`, so none of this ever blocks paint.

```
tools/refresh-google-fonts.sh
git diff --stat   # then commit src/fonts.css and src/assets/fonts/
```

The gstatic URLs it captures are versioned and immutable, so fonts stay frozen at the snapshot
until this is re-run. Run it to pick up font updates, or after editing `FAMILIES` in the script to
add a family or weight — editing `fonts.css` or `assets/fonts/` by hand will be overwritten (the
script wipes and repopulates `assets/fonts/` from scratch each run).

## Adding a new board mascot

Mascots are the little characters shown in the corner of a board and offered in the
board-settings picker. End to end:

1. **Get a transparent PNG.** Start from a photo of the object. If it has a plain
   background, remove it with `background-remove.py` (uses `rembg`; edit the input/
   output paths near the bottom of the file before running). The pineapple, robot,
   and pencil-cup were prepped this way.

2. **Level the bottom gap** so the object "sits" like the others rather than floating.
   Every mascot renders at a fixed CSS height (`.pineapple-pet { height: 420px }` in
   `tasker-frontend/src/index.css`), so the gap is matched as a *fraction of image
   height*, not absolute pixels.

   ```bash
   python3 tools/level-bottom-gap.py --dry-run my_mascot.png   # preview
   python3 tools/level-bottom-gap.py my_mascot.png             # apply (matches pineapple.webp's ratio)
   ```

   Paths resolve relative to `tasker-frontend/src/assets/`. By default it matches the
   reference's bottom-gap ratio (`--reference pineapple.webp`, ≈1.8%); override with
   `--gap` or switch to `--mode pixels` if you ever need absolute matching.

3. **Convert to WebP** — much smaller than PNG with no visible loss for these
   photographic-with-transparency images:

   ```bash
   python3 tools/to-webp.py my_mascot.png        # writes my_mascot.webp (lossy q82)
   ```

   Use `--quality N` to tune, or `--lossless` for flat/line art. Then remove the
   source PNG (the app imports the `.webp`).

4. **Generate the mobile `-sm` variant**, used in the `srcset` so phones don't fetch
   the desktop-sized asset:

   ```bash
   python3 tools/make-mascot-srcset.py my_mascot.webp   # writes my_mascot-sm.webp
   ```

   Note the printed `<width>x<height>` — you'll need the sm and full widths for the
   `srcSet` string in the next step.

5. **Register it (id must match on both sides):**
   - Frontend — add an entry to `MASCOTS` in `tasker-frontend/src/mascots.ts`
     (`import` both the `.webp` and the `-sm.webp`, give it an `id`, a `label`, and a
     `srcSet` built from the widths step 4 printed, e.g. `` `${smUrl} 232w, ${url} 479w` ``).
     Order is the picker order; the first entry is the default.
   - Backend — add a matching value to `BoardMascot` in
     `src/main/kotlin/dev/itayp/tasker/model/BoardMascot.kt` (the `id` string must equal
     the frontend `id`). Unknown/null ids fall back to `DEFAULT`, so old boards are safe.

   No DB migration is needed — the mascot is a plaintext `board.mascot` column added in
   changeset `005-board-mascot.xml`.

## Scripts

- **`background-remove.py`** — removes a white/near-white background via `rembg`.
  Input/output paths are edited inline; requires `pip install rembg`.
- **`level-bottom-gap.py`** — normalizes the bottom transparent gap to a reference
  image's height ratio (see step 2). Supports `--dry-run`, `--reference`, `--mode`, `--gap`.
- **`to-webp.py`** — converts PNG(s) to WebP with alpha. Supports `--quality`, `--lossless`.
- **`make-mascot-srcset.py`** — resizes a mascot WebP down to a `-sm` companion sized
  for the mobile breakpoint (see step 4). Supports `--height`, `--quality`.
- **`make-favicon.py`** — takes a square-ish app-icon concept on a white background,
  crops tight and fades the backdrop to transparency, and writes the full favicon set
  (`favicon.ico`, `favicon-{16,32,48}x{16,32,48}.png`), the opaque `apple-touch-icon.png`,
  and the opaque `icon-{192,512}x{192,512}.png` pair referenced by `public/manifest.json`
  — all from one source image, so regenerating the icon regenerates every size together.
- **`i18n-inventory.mjs`** — heuristic scan of `tasker-frontend/src/**/*.tsx` for hardcoded
  user-facing strings, sized per component. Feeds `../docs/archive/I18N-INVENTORY.md` (the i18n
  string-extraction checklist); re-run after a batch of extraction PRs. Run with
  `node ../tools/i18n-inventory.mjs` from `tasker-frontend/`. Requires only Node (no deps).
