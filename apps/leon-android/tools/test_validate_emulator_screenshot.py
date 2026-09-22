import unittest

from PIL import Image

from validate_emulator_screenshot import foreground_bounds, validate_screenshot


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
