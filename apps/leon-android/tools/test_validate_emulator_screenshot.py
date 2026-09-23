import unittest

from PIL import Image

from validate_emulator_screenshot import (
    changed_pixel_count,
    foreground_bounds,
    validate_screenshot,
)


class EmulatorScreenshotValidatorTest(unittest.TestCase):
    def test_finds_full_body_against_ci_host_background(self):
        image = Image.new("RGB", (200, 400), (1, 2, 3))
        px = image.load()
        for y in range(60, 340):
            for x in range(80, 121):
                px[x, y] = (120, 90, 75)
        for y in range(325, 340):
            for x in range(72, 129):
                px[x, y] = (245, 245, 245)

        self.assertEqual((72, 60, 128, 339), foreground_bounds(image))
        report = validate_screenshot(image, min_height_fraction=0.5)
        self.assertTrue(report["valid"])
        self.assertGreater(report["height_fraction"], 0.6)

    def test_detects_identical_state_frames_as_no_motion(self):
        image = Image.new("RGB", (120, 240), (1, 2, 3))
        px = image.load()
        for y in range(20, 220):
            for x in range(40, 80):
                px[x, y] = (30, 25, 20)
        self.assertEqual(changed_pixel_count(image, image.copy()), 0)

    def test_detects_visible_state_motion(self):
        base = Image.new("RGB", (120, 240), (1, 2, 3))
        variant = base.copy()
        base_px = base.load()
        variant_px = variant.load()
        for y in range(20, 220):
            for x in range(40, 80):
                base_px[x, y] = (30, 25, 20)
                variant_px[x, y] = (30, 25, 20)
        for y in range(80, 100):
            for x in range(70, 90):
                variant_px[x, y] = (120, 80, 60)
        self.assertGreaterEqual(changed_pixel_count(base, variant), 300)

    def test_rejects_missing_lower_body(self):
        image = Image.new("RGB", (200, 400), (1, 2, 3))
        px = image.load()
        for y in range(80, 160):
            for x in range(80, 121):
                px[x, y] = (120, 90, 75)
        report = validate_screenshot(image, min_height_fraction=0.5)
        self.assertFalse(report["valid"])

    def test_rejects_neon_green_character_fringe(self):
        image = Image.new("RGB", (200, 400), (1, 2, 3))
        px = image.load()
        for y in range(60, 340):
            for x in range(80, 121):
                px[x, y] = (0, 240, 0)
        report = validate_screenshot(image, min_height_fraction=0.5)
        self.assertFalse(report["valid"])
        self.assertGreater(report["green_fraction"], 0.01)


if __name__ == "__main__":
    unittest.main()
