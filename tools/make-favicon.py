"""Generate browser favicon assets (.ico + PNGs) from a square-ish app-icon image.

Crops tight to the icon (trimming excess white canvas) and fades the white
backdrop to transparency, then exports the standard sizes browsers/OSes ask
for. The apple touch icon is kept opaque (white-filled) since iOS doesn't
render transparency in home-screen icons reliably.

Usage:
    python3 tools/make-favicon.py path/to/concept.png
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image

PNG_SIZES = {
    "favicon-16x16.png": 16,
    "favicon-32x32.png": 32,
    "favicon-48x48.png": 48,
}
APPLE_TOUCH_ICON_SIZE = 180
ICO_SIZES = [(16, 16), (32, 32), (48, 48)]


def tight_bbox(img: Image.Image, threshold: int) -> tuple[int, int, int, int]:
    arr = np.array(img.convert("RGB"))
    mask = np.any(arr < threshold, axis=-1)
    ys, xs = np.where(mask)
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def fade_white_to_alpha(img: Image.Image, floor: int, ceiling: int) -> Image.Image:
    """Turns near-white pixels transparent, ramping alpha linearly between
    `floor` (fully opaque) and `ceiling` (fully transparent), so soft shadow
    edges fade out smoothly instead of leaving a hard cutout ring. Also
    un-premultiplies the white backdrop out of partially-transparent pixels'
    RGB, otherwise they'd carry a faint white halo over a dark page."""
    arr = np.array(img.convert("RGB")).astype(np.float32)
    whiteness = arr.min(axis=-1)
    alpha = 255 - np.clip((whiteness - floor) / (ceiling - floor), 0, 1) * 255
    a = np.clip(alpha / 255.0, 1e-3, 1.0)[..., None]
    true_rgb = np.clip((arr - (1 - a) * 255) / a, 0, 255)
    rgba = np.dstack([true_rgb, alpha]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input")
    parser.add_argument("--out-dir", default="tasker-frontend/public")
    parser.add_argument("--threshold", type=int, default=250, help="below this (0-255) on any RGB channel counts as content, not background")
    parser.add_argument("--padding", type=float, default=0.02, help="fraction of the cropped icon's side added back on each edge")
    parser.add_argument("--alpha-floor", type=int, default=210, help="min-channel value at/below which a pixel stays fully opaque")
    parser.add_argument("--alpha-ceiling", type=int, default=245, help="min-channel value at/above which a pixel is fully transparent")
    args = parser.parse_args()

    img = Image.open(args.input).convert("RGB")
    x0, y0, x1, y1 = tight_bbox(img, args.threshold)
    crop = img.crop((x0, y0, x1, y1))

    pad = round(max(crop.width, crop.height) * args.padding)
    side = max(crop.width, crop.height) + pad * 2
    canvas = Image.new("RGB", (side, side), (255, 255, 255))
    canvas.paste(crop, ((side - crop.width) // 2, (side - crop.height) // 2))

    transparent = fade_white_to_alpha(canvas, args.alpha_floor, args.alpha_ceiling)

    out_dir = Path(args.out_dir)
    for name, size in PNG_SIZES.items():
        transparent.resize((size, size), Image.LANCZOS).save(out_dir / name)

    transparent.resize((48, 48), Image.LANCZOS).save(out_dir / "favicon.ico", sizes=ICO_SIZES)

    opaque = Image.alpha_composite(Image.new("RGBA", transparent.size, (255, 255, 255, 255)), transparent).convert("RGB")
    opaque.resize((APPLE_TOUCH_ICON_SIZE, APPLE_TOUCH_ICON_SIZE), Image.LANCZOS).save(out_dir / "apple-touch-icon.png")

    print(f"Wrote {', '.join(PNG_SIZES)}, favicon.ico and apple-touch-icon.png to {out_dir}")


if __name__ == "__main__":
    main()
