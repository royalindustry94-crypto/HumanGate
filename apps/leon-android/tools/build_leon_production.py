#!/usr/bin/env python3
import argparse
import hashlib
import json
from collections import deque
from pathlib import Path

from PIL import Image, ImageEnhance

MESH_COLS = 48
MESH_ROWS = 84
DESIGN_SIZE = [384, 768]


def read_landmarks(path: Path) -> tuple[float, float, float]:
    values = path.read_text(encoding="utf-8").replace(",", " ").split()
    if len(values) < 3:
        raise ValueError("Leon landmarks require crownY soleY centreX")
    crown, sole, centre = map(float, values[:3])
    if crown < 0 or sole <= crown or centre <= 0:
        raise ValueError(f"Invalid Leon landmarks: {crown} {sole} {centre}")
    return crown, sole, centre


def _is_green(pixel) -> bool:
    r, g, b, a = pixel
    return a != 0 and g >= 95 and g - r >= 22 and g - b >= 22


def key_green(image: Image.Image) -> Image.Image:
    src = image.convert("RGBA")
    w, h = src.size
    pixels = list(src.getdata())
    candidate = [_is_green(p) for p in pixels]
    background = [False] * (w * h)
    q = deque()

    def seed(i: int) -> None:
        if 0 <= i < len(candidate) and candidate[i] and not background[i]:
            background[i] = True
            q.append(i)

    for x in range(w):
        seed(x)
        seed((h - 1) * w + x)
    for y in range(h):
        seed(y * w)
        seed(y * w + w - 1)

    while q:
        i = q.popleft()
        x, y = i % w, i // w
        if x > 0:
            seed(i - 1)
        if x + 1 < w:
            seed(i + 1)
        if y > 0:
            seed(i - w)
        if y + 1 < h:
            seed(i + w)

    near = background[:]
    for _ in range(2):
        expanded = near[:]
        for i, marked in enumerate(near):
            if not marked:
                continue
            x, y = i % w, i // w
            if x > 0:
                expanded[i - 1] = True
            if x + 1 < w:
                expanded[i + 1] = True
            if y > 0:
                expanded[i - w] = True
            if y + 1 < h:
                expanded[i + w] = True
        near = expanded

    out = []
    for i, (r, g, b, a) in enumerate(pixels):
        if background[i]:
            out.append((r, g, b, 0))
            continue
        # A green pixel that is itself a key candidate but was NOT reached from the
        # boundary is intentional interior detail (for Leon, e.g. a gemstone).
        # De-spill only non-candidate edge pixels contaminated by the keyed screen.
        if candidate[i]:
            out.append((r, g, b, a))
            continue
        if near[i] and a:
            max_rb = max(r, b)
            dominance = g - max_rb
            if g >= 120 and dominance >= 80:
                out.append((r, g, b, 0))
            elif dominance > 8:
                out.append((r, min(255, max_rb + 8), b, a))
            else:
                out.append((r, g, b, a))
        else:
            out.append((r, g, b, a))

    keyed = Image.new("RGBA", (w, h))
    keyed.putdata(out)
    return keyed


def extract_reference_master(source: Image.Image) -> Image.Image:
    """Extract Leon's production texture from his committed green-screen reference photo.

    An earlier version of this function hand-drew a polygon mask over a ~95x345px crop of the
    low-resolution character-sheet contact print (docs/leon-reference/leon-character-sheet.png)
    and upscaled it ~1.7x. That crop's hoodie and background share the same luminance in the
    sheet, so the hand-drawn polygon could not trace the true edge: it left dark cutout residue
    around the head and left side and produced faded, torn-looking lower legs after upscaling a
    tiny, imprecise source. See docs/leon-reference/README.md.

    This extracts from docs/leon-reference/leon-front-source.png instead: a full-resolution
    (941x1672) front-facing photo on a real chroma-green background. key_green() — a proper
    flood-fill key from the border plus edge despill, not a hand-authored outline — removes the
    background cleanly at native resolution, so there is no polygon to get wrong and no large
    upscale to blur the edges.
    """
    src = source.convert("RGBA")
    keyed = key_green(src)

    box = keyed.getchannel("A").getbbox()
    if box is None:
        raise ValueError("Leon front-source extraction produced no pixels")
    figure = keyed.crop(box)

    scale = min(330.0 / figure.width, 600.0 / figure.height)
    size = (round(figure.width * scale), round(figure.height * scale))
    figure = figure.resize(size, Image.Resampling.LANCZOS)

    # A mild post-downscale sharpen helps preserve tattoo/hoodie edges at phone-overlay size.
    rgb = ImageEnhance.Sharpness(figure.convert("RGB")).enhance(1.35)
    figure = Image.merge("RGBA", (*rgb.split(), figure.getchannel("A")))

    canvas = Image.new("RGBA", (360, 640), (0, 0, 0, 0))
    canvas.alpha_composite(figure, ((360 - figure.width) // 2, 20))
    return canvas


def alpha_bounds(image: Image.Image) -> tuple[int, int, int, int]:
    alpha = image.convert("RGBA").getchannel("A")
    box = alpha.getbbox()
    if box is None:
        raise ValueError("Leon production texture is fully transparent")
    left, top, right_exclusive, bottom_exclusive = box
    return left, top, right_exclusive - 1, bottom_exclusive - 1


def build_manifest(source: Image.Image, keyed: Image.Image, source_bytes: bytes,
                   landmarks: tuple[float, float, float]) -> dict:
    left, top, right, bottom = alpha_bounds(keyed)
    crown, sole, centre = landmarks
    return {
        "schema": 1,
        "source_width": source.width,
        "source_height": source.height,
        "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "mesh_cols": MESH_COLS,
        "mesh_rows": MESH_ROWS,
        "design_size": DESIGN_SIZE,
        "landmarks": {
            "crown_y": crown,
            "sole_y": sole,
            "centre_x": centre,
        },
        "alpha_bounds": {
            "left": left,
            "top": top,
            "right": right,
            "bottom": bottom,
        },
    }


def build(source_path: Path, landmarks_path: Path, output_dir: Path) -> None:
    source_bytes = source_path.read_bytes()
    with Image.open(source_path) as opened:
        sheet = opened.convert("RGBA")
    master = extract_reference_master(sheet)
    landmarks = read_landmarks(landmarks_path)
    manifest = build_manifest(master, master, source_bytes, landmarks)
    manifest["reference_width"] = sheet.width
    manifest["reference_height"] = sheet.height
    manifest["reference_source"] = "docs/leon-reference/leon-front-source.png"

    output_dir.mkdir(parents=True, exist_ok=True)
    master.save(output_dir / "leon.png", format="PNG", optimize=False)
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--landmarks", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    build(args.source, args.landmarks, args.out)


if __name__ == "__main__":
    main()
