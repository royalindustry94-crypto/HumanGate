import tempfile
import unittest
from pathlib import Path

from PIL import Image

from render_contract import alpha_bounds, green_spill_fraction, compare_images


class RenderContractTest(unittest.TestCase):
    def test_rest_pose_matches_production_texture(self):
        with tempfile.TemporaryDirectory() as td:
            base = Image.new("RGBA", (32, 64), (0, 0, 0, 0))
            px = base.load()
            for y in range(2, 62):
                for x in range(10, 22):
                    px[x, y] = (40, 30, 20, 255)
            a = Path(td) / "a.png"
            b = Path(td) / "b.png"
            base.save(a)
            base.save(b)
            result = compare_images(a, b)
            self.assertLessEqual(result["max_channel_error"], 2)
            self.assertLessEqual(result["changed_pixel_fraction"], 0.001)

    def test_green_spill_threshold_detects_clean_texture(self):
        clean = Image.new("RGBA", (10, 10), (30, 25, 20, 255))
        self.assertLessEqual(green_spill_fraction(clean), 0.0005)

    def test_alpha_bounds_include_full_character(self):
        image = Image.new("RGBA", (360, 640), (0, 0, 0, 0))
        px = image.load()
        for y in range(20, 620):
            for x in range(100, 261):
                px[x, y] = (40, 30, 20, 255)
        bounds = alpha_bounds(image)
        self.assertLessEqual(bounds[1], 20)
        self.assertGreaterEqual(bounds[3], 619)


if __name__ == "__main__":
    unittest.main()
