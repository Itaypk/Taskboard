# tools

Ad-hoc scripts for preparing assets. Not part of the build — run them by hand when
adding or reworking an asset, then commit the result.

Requires Python 3 with `Pillow` and `numpy` (and `rembg` only for background removal).

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

4. **Register it (id must match on both sides):**
   - Frontend — add an entry to `MASCOTS` in `tasker-frontend/src/mascots.ts`
     (`import` the `.webp`, give it an `id` and a `label`). Order is the picker order;
     the first entry is the default.
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
- **`i18n-inventory.mjs`** — heuristic scan of `tasker-frontend/src/**/*.tsx` for hardcoded
  user-facing strings, sized per component. Feeds `../docs/archive/I18N-INVENTORY.md` (the i18n
  string-extraction checklist); re-run after a batch of extraction PRs. Run with
  `node ../tools/i18n-inventory.mjs` from `tasker-frontend/`. Requires only Node (no deps).
