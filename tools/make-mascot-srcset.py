#!/usr/bin/env python3
"""
Generate a mobile-sized `-sm` companion WebP for a mascot image, for use in a
`srcset`. Mascots render at a fixed CSS height per breakpoint (420px desktop,
320px tablet, 220px mobile — see `.pineapple-pet` in tasker-frontend/src/index.css),
but ship as one asset sized for desktop.

The default --height (600) is sized against the mobile breakpoint's rendered *width*
(not the CSS height directly, since srcset candidates are picked by width) at a
~2.7x device-pixel-ratio target — comfortably above the 2.625 DPR Lighthouse's mobile
emulation profile uses (the same profile behind the PageSpeed score that flagged this
image as slow), so the sm variant actually gets picked on the devices that matter, not
just at DPR 1. A phone above ~2.7x DPR still falls back to the full asset; desktop is
unaffected either way. See mascots.ts for the per-mascot `sizes` this pairs with.

Usage:
    python3 tools/make-mascot-srcset.py pineapple.webp mr_roboto.webp stationery_holder.webp
    python3 tools/make-mascot-srcset.py --height 600 pineapple.webp

Writes `<name>-sm.webp` next to each source. Paths resolve relative to the assets dir.
"""
import argparse
import os
import sys

from PIL import Image

ASSETS_DIR = os.path.join(os.path.dirname(__file__), "..", "tasker-frontend", "src", "assets")


def resolve(path: str) -> str:
    if os.path.isfile(path):
        return path
    candidate = os.path.join(ASSETS_DIR, path)
    if os.path.isfile(candidate):
        return candidate
    sys.exit(f"File not found: {path}")


def make_sm(path: str, target_height: int, quality: int) -> None:
    out = os.path.splitext(path)[0] + "-sm.webp"
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    target_width = round(w * target_height / h)
    resized = img.resize((target_width, target_height), Image.LANCZOS)
    resized.save(out, "WEBP", quality=quality, method=6)

    src_kb = os.path.getsize(path) / 1024
    out_kb = os.path.getsize(out) / 1024
    name = os.path.basename(path)
    print(f"{name:26s} {w}x{h} {src_kb:7.1f} KB -> {os.path.basename(out):32s} "
          f"{target_width}x{target_height} {out_kb:7.1f} KB (-{(1 - out_kb / src_kb) * 100:.0f}%)")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("targets", nargs="+", help="mascot WebP(s) (relative to the assets dir)")
    p.add_argument("--height", type=int, default=600, help="target height in px (default: 600 — see module docstring)")
    p.add_argument("--quality", type=int, default=82, help="lossy WebP quality 0-100 (default: 82)")
    args = p.parse_args()

    for t in args.targets:
        make_sm(resolve(t), args.height, args.quality)


if __name__ == "__main__":
    main()
