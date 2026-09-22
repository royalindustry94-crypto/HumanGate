#!/usr/bin/env python3
import argparse
import hashlib
import json
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

MESH_COLS = 16
MESH_ROWS = 28
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


def extract_reference_master(sheet: Image.Image) -> Image.Image:
    """Extract the canonical front turnaround from the committed character sheet.

    The old bundled leon-front.webp is truncated and cannot be decoded reliably.
    This extraction is deterministic and uses only the exact committed reference art.
    """
    src = sheet.convert("RGBA")
    if src.size != (1223, 1286):
        raise ValueError(f"Unexpected Leon character-sheet size: {src.size}")

    # Canonical FRONT panel in docs/leon-reference/leon-character-sheet.png.
    crop = src.crop((820, 35, 930, 410))
    mask = Image.new("L", crop.size, 0)
    d = ImageDraw.Draw(mask)

    # Head / neck.
    d.polygon([
        (45, 11), (62, 11), (70, 16), (75, 27), (75, 44), (70, 55),
        (64, 62), (45, 62), (39, 56), (35, 46), (35, 30), (39, 18)
    ], fill=255)

    # Hoodie / arms / hands.
    d.polygon([
        (36, 58), (27, 61), (20, 68), (16, 80), (14, 101), (14, 124),
        (12, 145), (14, 163), (19, 176), (25, 181), (30, 176), (32, 168),
        (32, 181), (81, 181), (82, 168), (85, 176), (91, 181), (96, 176),
        (100, 162), (100, 140), (98, 114), (97, 92), (93, 76), (87, 65),
        (77, 60), (69, 57), (63, 66), (47, 66), (42, 58)
    ], fill=255)

    # Legs.
    d.polygon([
        (31, 176), (54, 176), (55, 214), (53, 252), (52, 287), (49, 320),
        (46, 329), (31, 329), (29, 319), (31, 292), (30, 258), (30, 220)
    ], fill=255)
    d.polygon([
        (56, 176), (82, 176), (81, 219), (82, 258), (81, 292), (84, 319),
        (82, 329), (65, 329), (61, 321), (60, 292), (59, 256), (57, 219)
    ], fill=255)

    # Shoes.
    d.polygon([
        (28, 322), (48, 322), (50, 328), (52, 333), (51, 340),
        (46, 344), (24, 344), (21, 341), (22, 333), (25, 327)
    ], fill=255)
    d.polygon([
        (64, 322), (84, 322), (88, 328), (91, 334), (90, 340),
        (86, 344), (64, 344), (61, 341), (61, 333)
    ], fill=255)

    mask = mask.filter(ImageFilter.GaussianBlur(0.45))
    cut = crop.copy()
    cut.putalpha(mask)
    cut = cut.crop((8, 5, 103, 350))
    box = cut.getchannel("A").getbbox()
    if box is None:
        raise ValueError("Leon front turnaround extraction produced no pixels")
    figure = cut.crop(box)

    scale = min(330.0 / figure.width, 600.0 / figure.height)
    size = (round(figure.width * scale), round(figure.height * scale))
    figure = figure.resize(size, Image.Resampling.LANCZOS)

    # A mild post-upscale sharpen helps preserve tattoo/hoodie edges at phone-overlay size.
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
    manifest["reference_source"] = "docs/leon-reference/leon-character-sheet.png"

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
