#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

from PIL import Image


def _distance(a, b):
    return max(abs(int(a[i]) - int(b[i])) for i in range(3))


def foreground_bounds(image: Image.Image):
    rgb = image.convert("RGB")
    w, h = rgb.size
    bg = rgb.getpixel((0, 0))
    xs = []
    ys = []
    for y in range(h):
        for x in range(w):
            p = rgb.getpixel((x, y))
            if _distance(p, bg) >= 24:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def _green_fraction(image: Image.Image, bounds):
    if bounds is None:
        return 0.0
    left, top, right, bottom = bounds
    rgb = image.convert("RGB")
    total = 0
    green = 0
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            r, g, b = rgb.getpixel((x, y))
            total += 1
            if g >= 150 and g - max(r, b) >= 75:
                green += 1
    return green / max(1, total)


def changed_pixel_count(image_a: Image.Image, image_b: Image.Image, threshold: int = 10):
    a = image_a.convert("RGB")
    b = image_b.convert("RGB")
    if a.size != b.size:
        return max(a.size[0] * a.size[1], b.size[0] * b.size[1])

    bounds_a = foreground_bounds(a)
    bounds_b = foreground_bounds(b)
    if bounds_a is None or bounds_b is None:
        return 0
    left = min(bounds_a[0], bounds_b[0])
    top = min(bounds_a[1], bounds_b[1])
    right = max(bounds_a[2], bounds_b[2])
    bottom = max(bounds_a[3], bounds_b[3])

    changed = 0
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            if _distance(a.getpixel((x, y)), b.getpixel((x, y))) >= threshold:
                changed += 1
    return changed


def validate_screenshot(image: Image.Image, min_height_fraction: float = 0.55):
    w, h = image.size
    bounds = foreground_bounds(image)
    if bounds is None:
        return {
            "valid": False,
            "bounds": None,
            "height_fraction": 0.0,
            "green_fraction": 0.0,
            "reaches_upper_third": False,
            "reaches_lower_third": False,
        }

    left, top, right, bottom = bounds
    height_fraction = (bottom - top + 1) / max(1, h)
    reaches_upper = top < h / 3.0
    reaches_lower = bottom > h * 2.0 / 3.0
    green_fraction = _green_fraction(image, bounds)
    valid = (
        height_fraction >= min_height_fraction
        and reaches_upper
        and reaches_lower
        and green_fraction <= 0.01
    )
    return {
        "valid": valid,
        "bounds": [left, top, right, bottom],
        "height_fraction": height_fraction,
        "green_fraction": green_fraction,
        "reaches_upper_third": reaches_upper,
        "reaches_lower_third": reaches_lower,
        "image_size": [w, h],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument("--min-height-fraction", type=float, default=0.55)
    parser.add_argument(
        "--require-change",
        action="append",
        nargs=3,
        metavar=("BASE", "VARIANT", "MIN_CHANGED_PIXELS"),
        default=[],
        help="Require a real rendered variant to differ from its base by at least N pixels.",
    )
    args = parser.parse_args()

    failed = False
    reports = {}
    loaded = {}
    for path in args.images:
        with Image.open(path) as image:
            loaded[str(path)] = image.convert("RGB")
            report = validate_screenshot(image, args.min_height_fraction)
        reports[str(path)] = report
        if not report["valid"]:
            failed = True

    change_reports = {}
    for base_name, variant_name, min_pixels_text in args.require_change:
        base = loaded.get(base_name)
        variant = loaded.get(variant_name)
        if base is None or variant is None:
            change_reports[f"{base_name}->{variant_name}"] = {
                "valid": False,
                "reason": "image not included in positional image list",
            }
            failed = True
            continue
        min_pixels = int(min_pixels_text)
        changed = changed_pixel_count(base, variant)
        valid = changed >= min_pixels
        change_reports[f"{base_name}->{variant_name}"] = {
            "valid": valid,
            "changed_pixels": changed,
            "min_changed_pixels": min_pixels,
        }
        if not valid:
            failed = True
    reports["_required_changes"] = change_reports

    print(json.dumps(reports, indent=2, sort_keys=True))
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
