#!/usr/bin/env python3
"""
Split the goofy-balloon source sheet into standalone WebP parts for the
"task done" celebration animation.

The source (`tools/Balloon.png`) is one transparent-background photo holding the
balloon-with-googly-eyes plus twelve pipe-cleaner mouths laid out in a 3x4 grid
below it. The parts never touch, so connected-component labelling on the alpha
channel recovers them exactly -- no hand-cropping, and re-running after a
re-shoot picks up the new artwork.

Mouths are photographed at the same scale as the balloon, so each one is
composited at its *natural* size relative to the balloon and centred on a single
shared anchor -- no per-mouth tuning table. The manifest therefore stores sizes
as fractions of the balloon, which keeps the layering resolution-independent.

Usage:
    python3 tools/split-balloon-faces.py                 # write assets + manifest
    python3 tools/split-balloon-faces.py --sheet out.png # also render a preview grid
    python3 tools/split-balloon-faces.py --dry-run
"""
import argparse
import json
import os

import numpy as np
from PIL import Image
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "Balloon.png")
OUT_DIR = os.path.join(HERE, "..", "tasker-frontend", "src", "assets", "balloon")

# Reading order, left-to-right within each row of the source grid.
MOUTH_NAMES = [
    "grin", "open-smile", "squiggle",
    "tongue-out", "flat", "frown",
    "zigzag", "heart", "smirk",
    "mustache", "stitched", "wobble",
]

# Where a mouth's bounding-box centre lands, as a fraction of the balloon crop.
ANCHOR = (0.50, 0.655)

# Below this the pixel is halo, not artwork; it only gates *detection* -- the
# original soft alpha is preserved inside each part so edges stay anti-aliased.
ALPHA_FLOOR = 40
MIN_AREA = 300


def extract_parts(img):
    rgba = np.array(img)
    labels, _ = ndimage.label(rgba[..., 3] > ALPHA_FLOOR, structure=np.ones((3, 3)))
    parts = []
    for index in range(1, labels.max() + 1):
        mask = labels == index
        if mask.sum() < MIN_AREA:
            continue
        ys, xs = np.where(mask)
        box = (ys.min(), ys.max() + 1, xs.min(), xs.max() + 1)
        crop = rgba[box[0]:box[1], box[2]:box[3]].copy()
        # Zero out any neighbouring part that shares the bounding box.
        crop[..., 3] = np.where(mask[box[0]:box[1], box[2]:box[3]], crop[..., 3], 0)
        parts.append({"y": int(box[0]), "x": int(box[2]), "img": Image.fromarray(crop)})
    return parts


def reading_order(mouths):
    """Group by row (mouths in a row overlap vertically), then sort by x."""
    rows, current = [], []
    for mouth in sorted(mouths, key=lambda m: m["y"]):
        if current and mouth["y"] > current[0]["y"] + current[0]["img"].height * 0.6:
            rows.append(current)
            current = []
        current.append(mouth)
    rows.append(current)
    # A mis-grouped row would silently rename every mouth after it, and the
    # count check downstream wouldn't notice -- so pin the grid shape.
    if [len(r) for r in rows] != [3, 3, 3, 3]:
        raise SystemExit(f"Expected a 3-per-row grid, got rows of {[len(r) for r in rows]} -- "
                         "the source layout changed; revisit reading_order() and MOUTH_NAMES.")
    return [m for row in rows for m in sorted(row, key=lambda m: m["x"])]


def render_sheet(balloon, mouths, path):
    bw, bh = balloon.size
    cols = 4
    rows = (len(mouths) + cols - 1) // cols
    sheet = Image.new("RGBA", (bw * cols, bh * rows), (255, 255, 255, 255))
    for i, (_, mouth) in enumerate(mouths):
        cell = balloon.copy()
        cell.alpha_composite(mouth, (
            int(ANCHOR[0] * bw - mouth.width / 2),
            int(ANCHOR[1] * bh - mouth.height / 2),
        ))
        sheet.alpha_composite(cell, ((i % cols) * bw, (i // cols) * bh))
    sheet.convert("RGB").save(path)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", default=SOURCE)
    p.add_argument("--out", default=OUT_DIR)
    p.add_argument("--quality", type=int, default=88, help="lossy WebP quality (default: 88)")
    p.add_argument("--sheet", help="also render a preview grid of every mouth on the balloon")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    parts = extract_parts(Image.open(args.input).convert("RGBA"))
    parts.sort(key=lambda part: part["img"].width * part["img"].height, reverse=True)
    balloon, mouths = parts[0]["img"], reading_order(parts[1:])

    if len(mouths) != len(MOUTH_NAMES):
        raise SystemExit(f"Found {len(mouths)} mouths but have {len(MOUTH_NAMES)} names -- "
                         "the source layout changed; update MOUTH_NAMES.")

    named = list(zip(MOUTH_NAMES, [m["img"] for m in mouths]))
    bw, bh = balloon.size
    manifest = {
        "balloon": {"file": "balloon.webp", "width": bw, "height": bh, "mouthAnchor": {"x": ANCHOR[0], "y": ANCHOR[1]}},
        "mouths": [
            {
                "id": name,
                "file": f"mouth-{name}.webp",
                # Fractions of the balloon box, so a mouth can be positioned with
                # percentages against whatever size the balloon is rendered at.
                "width": round(img.width / bw, 4),
                "height": round(img.height / bh, 4),
            }
            for name, img in named
        ],
    }

    for entry, (_, img) in zip(manifest["mouths"], named):
        print(f"{entry['id']:12s} {img.width:4d}x{img.height:<4d}  "
              f"{entry['width'] * 100:5.1f}% x {entry['height'] * 100:5.1f}% of balloon")
    print(f"{'balloon':12s} {bw:4d}x{bh:<4d}")

    if args.sheet:
        render_sheet(balloon, named, args.sheet)
        print(f"preview -> {args.sheet}")

    if args.dry_run:
        return

    os.makedirs(args.out, exist_ok=True)
    for name, img in [("balloon", balloon)] + [(f"mouth-{n}", i) for n, i in named]:
        img.save(os.path.join(args.out, f"{name}.webp"), "WEBP", quality=args.quality, method=6)
    with open(os.path.join(args.out, "faces.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    total = sum(os.path.getsize(os.path.join(args.out, f)) for f in os.listdir(args.out))
    print(f"wrote {len(named) + 2} files to {os.path.relpath(args.out, os.path.join(HERE, '..'))} ({total / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
