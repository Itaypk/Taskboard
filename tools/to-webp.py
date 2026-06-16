#!/usr/bin/env python3
"""
Convert PNG assets to WebP (with alpha) to shrink the bundled mascots/images.

Lossy WebP with an alpha channel is dramatically smaller than PNG for these
photographic-with-transparency mascots, with no visible loss at the default
quality. Use --lossless for line art / flat graphics where lossy artifacts show.

Usage:
    python3 tools/to-webp.py mr_roboto.png stationery_holder.png pineapple.png
    python3 tools/to-webp.py --quality 90 pineapple.png
    python3 tools/to-webp.py --lossless some_logo.png

Writes `<name>.webp` next to each source. Paths resolve relative to the assets
dir. The source PNG is left in place; remove it (and update imports) yourself.
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


def convert(path: str, quality: int, lossless: bool) -> None:
    out = os.path.splitext(path)[0] + ".webp"
    img = Image.open(path).convert("RGBA")
    # method=6 is the slowest/best-compressing effort; fine for a one-off asset step.
    img.save(out, "WEBP", quality=quality, lossless=lossless, method=6)

    src_kb = os.path.getsize(path) / 1024
    out_kb = os.path.getsize(out) / 1024
    name = os.path.basename(path)
    mode = "lossless" if lossless else f"q{quality}"
    print(f"{name:26s} {src_kb:7.1f} KB -> {os.path.basename(out):28s} {out_kb:7.1f} KB ({mode}, -{(1 - out_kb / src_kb) * 100:.0f}%)")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("targets", nargs="+", help="PNG(s) to convert (relative to the assets dir)")
    p.add_argument("--quality", type=int, default=82, help="lossy quality 0-100 (default: 82)")
    p.add_argument("--lossless", action="store_true", help="use lossless WebP instead of lossy")
    args = p.parse_args()

    for t in args.targets:
        convert(resolve(t), args.quality, args.lossless)


if __name__ == "__main__":
    main()
