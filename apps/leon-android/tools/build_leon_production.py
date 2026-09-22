#!/usr/bin/env python3
import argparse
import hashlib
import json
from collections import deque
from pathlib import Path

from PIL import Image

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
        source = opened.convert("RGBA")
    landmarks = read_landmarks(landmarks_path)
    keyed = key_green(source)
    manifest = build_manifest(source, keyed, source_bytes, landmarks)

    output_dir.mkdir(parents=True, exist_ok=True)
    keyed.save(output_dir / "leon.png", format="PNG", optimize=False)
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
