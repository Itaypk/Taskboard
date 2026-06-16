#!/usr/bin/env python3
"""
Level the bottom transparent gap of mascot PNGs so they "sit" consistently.

The "gap" is the band of fully/near-transparent rows between the image's bottom
edge and the lowest non-transparent pixel. The mascots render at a fixed CSS
height (`height: 420px`), so what makes an object sit like the pineapple is the
gap *as a fraction of image height* — not an absolute pixel count (13px out of a
738px image looks nothing like 13px out of a 1506px image once both scale to
420px). So the default mode matches the reference image's gap *ratio*; an
absolute-pixel mode is available via `--mode pixels`.

Adjustment is bottom-only: existing bottom transparent rows are stripped, then
the desired amount is padded back. Top/left/right padding is left untouched.

Usage:
    python3 tools/level-bottom-gap.py mr_roboto.png stationery_holder.png
    python3 tools/level-bottom-gap.py --mode pixels --gap 13 mr_roboto.png
    python3 tools/level-bottom-gap.py --reference pineapple.png --dry-run *.png

Paths are resolved relative to the assets dir unless absolute / already valid.
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image

ASSETS_DIR = os.path.join(os.path.dirname(__file__), "..", "tasker-frontend", "src", "assets")
ALPHA_THRESHOLD = 10  # treat alpha <= this as "transparent" when locating content


def resolve(path: str) -> str:
    if os.path.isfile(path):
        return path
    candidate = os.path.join(ASSETS_DIR, path)
    if os.path.isfile(candidate):
        return candidate
    sys.exit(f"File not found: {path}")


def content_rows(alpha: np.ndarray) -> np.ndarray:
    """Indices of rows containing at least one non-transparent pixel."""
    return np.where(alpha.max(axis=1) > ALPHA_THRESHOLD)[0]


def bottom_gap(path: str) -> tuple[int, int]:
    """Returns (bottom_gap_px, height) for an image."""
    alpha = np.array(Image.open(path).convert("RGBA"))[:, :, 3]
    rows = content_rows(alpha)
    if len(rows) == 0:
        sys.exit(f"{path} is fully transparent")
    height = alpha.shape[0]
    return height - 1 - int(rows[-1]), height


def level(path: str, target_ratio: float | None, target_px: int | None, dry_run: bool) -> None:
    img = Image.open(path).convert("RGBA")
    arr = np.array(img)
    alpha = arr[:, :, 3]
    rows = content_rows(alpha)
    if len(rows) == 0:
        sys.exit(f"{path} is fully transparent")

    last = int(rows[-1])
    height = arr.shape[0]
    old_gap = height - 1 - last

    # Strip every transparent row below the content, then pad the desired amount back.
    stripped = arr[: last + 1, :, :]
    h0 = stripped.shape[0]

    if target_px is not None:
        pad = target_px
    else:
        # pad / (h0 + pad) = ratio  ->  pad = ratio * h0 / (1 - ratio)
        pad = round(target_ratio * h0 / (1 - target_ratio))

    new_arr = np.concatenate([stripped, np.zeros((pad, arr.shape[1], 4), dtype=arr.dtype)], axis=0)
    new_h = new_arr.shape[0]
    new_ratio = pad / new_h * 100

    name = os.path.basename(path)
    print(f"{name:26s} gap {old_gap}px ({old_gap / height * 100:.1f}%) -> {pad}px ({new_ratio:.1f}%)")
    if not dry_run:
        Image.fromarray(new_arr, "RGBA").save(path, "PNG")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("targets", nargs="+", help="PNG(s) to adjust (relative to the assets dir)")
    p.add_argument("--reference", default="pineapple.png", help="image whose gap is matched (default: pineapple.png)")
    p.add_argument("--mode", choices=["ratio", "pixels"], default="ratio", help="match the reference gap as a height ratio (default) or absolute pixels")
    p.add_argument("--gap", type=float, help="override the target gap (ratio 0-1 in ratio mode, or pixels in pixels mode)")
    p.add_argument("--dry-run", action="store_true", help="report changes without writing")
    args = p.parse_args()

    ref_gap_px, ref_h = bottom_gap(resolve(args.reference))
    if args.mode == "pixels":
        target_px = int(args.gap) if args.gap is not None else ref_gap_px
        target_ratio = None
        print(f"Reference: {args.reference} bottom gap {ref_gap_px}px — matching {target_px}px absolute\n")
    else:
        target_ratio = args.gap if args.gap is not None else ref_gap_px / ref_h
        target_px = None
        print(f"Reference: {args.reference} bottom gap {ref_gap_px}/{ref_h}px — matching {target_ratio * 100:.1f}% of height\n")

    for t in args.targets:
        level(resolve(t), target_ratio, target_px, args.dry_run)


if __name__ == "__main__":
    main()
