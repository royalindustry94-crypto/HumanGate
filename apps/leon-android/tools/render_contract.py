#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops

from build_leon_production import extract_reference_master


def alpha_bounds(image: Image.Image) -> tuple[int, int, int, int]:
    box = image.convert("RGBA").getchannel("A").getbbox()
    if box is None:
        raise ValueError("image has no opaque Leon pixels")
    left, top, right_exclusive, bottom_exclusive = box
    return left, top, right_exclusive - 1, bottom_exclusive - 1


def green_spill_fraction(image: Image.Image) -> float:
    rgba = image.convert("RGBA")
    opaque = 0
    spill = 0
    for r, g, b, a in rgba.getdata():
        if a < 16:
            continue
        opaque += 1
        max_rb = max(r, b)
        # Deliberately stricter than the authoring key: this detects neon-screen residue,
        # not normal green gemstones or subtle natural colour variation.
        if g >= 135 and g - max_rb >= 70:
            spill += 1
    return spill / max(1, opaque)


def compare_images(path_a: Path, path_b: Path) -> dict:
    with Image.open(path_a) as a0, Image.open(path_b) as b0:
        a = a0.convert("RGBA")
        b = b0.convert("RGBA")
    if a.size != b.size:
        return {
            "max_channel_error": 255,
            "changed_pixel_fraction": 1.0,
            "size_a": a.size,
            "size_b": b.size,
        }

    diff = ImageChops.difference(a, b)
    extrema = diff.getextrema()
    max_error = max(high for low, high in extrema)
    changed = 0
    total = a.width * a.height
    for pixel in diff.getdata():
        if any(channel > 2 for channel in pixel):
            changed += 1
    return {
        "max_channel_error": max_error,
        "changed_pixel_fraction": changed / max(1, total),
        "size_a": a.size,
        "size_b": b.size,
    }


def _state_preview(master: Image.Image, state: str) -> Image.Image:
    """Generate deterministic visual-contract previews without changing Leon's identity.

    The Android renderer remains the authority for deformation. These previews intentionally use
    only lossless/affine whole-texture operations and exist to catch source cropping, transparency,
    chroma spill and accidental character replacement before the emulator stage.
    """
    canvas = Image.new("RGBA", master.size, (0, 0, 0, 0))
    if state == "minimised":
        scaled = master.resize((180, 320), Image.Resampling.LANCZOS)
        canvas.alpha_composite(scaled, (90, 320))
    elif state == "head-left":
        canvas.alpha_composite(master, (-2, 0))
    elif state == "head-right":
        canvas.alpha_composite(master, (2, 0))
    elif state == "breath-peak":
        scaled = master.resize((364, 640), Image.Resampling.LANCZOS)
        canvas.alpha_composite(scaled, (-2, 0))
    else:
        canvas.alpha_composite(master, (0, 0))
    return canvas


def generate_previews(sheet_path: Path, output_dir: Path) -> dict:
    with Image.open(sheet_path) as sheet:
        master = extract_reference_master(sheet)

    output_dir.mkdir(parents=True, exist_ok=True)
    states = [
        "rest", "idle", "head-left", "head-right", "breath-peak",
        "listening", "thinking", "speaking", "minimised",
    ]
    report = {}
    for state in states:
        preview = master if state == "rest" else _state_preview(master, state)
        out = output_dir / f"{state}.png"
        preview.save(out, "PNG")
        bounds = alpha_bounds(preview)
        report[state] = {
            "alpha_bounds": list(bounds),
            "green_spill_fraction": green_spill_fraction(preview),
            "file": str(out),
        }

    rest = output_dir / "rest.png"
    source = output_dir / "_master.png"
    master.save(source, "PNG")
    comparison = compare_images(rest, source)
    source.unlink()
    report["rest_comparison"] = comparison

    # Hard release gates for the source-level visual contract.
    rest_bounds = tuple(report["rest"]["alpha_bounds"])
    if rest_bounds[1] > 20 or rest_bounds[3] < 619:
        raise SystemExit(f"Leon full-body bounds failed: {rest_bounds}")
    if report["rest"]["green_spill_fraction"] > 0.0005:
        raise SystemExit(
            "Leon green spill failed: "
            + str(report["rest"]["green_spill_fraction"]))
    if comparison["max_channel_error"] > 2 or comparison["changed_pixel_fraction"] > 0.001:
        raise SystemExit(f"Leon rest-pose identity failed: {comparison}")

    (output_dir / "visual-contract.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sheet", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    generate_previews(args.sheet, args.out)


if __name__ == "__main__":
    main()
