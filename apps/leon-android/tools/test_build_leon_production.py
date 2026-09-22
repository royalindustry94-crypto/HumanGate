import hashlib
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from build_leon_production import read_landmarks, key_green, alpha_bounds, build_manifest


class BuildLeonProductionTest(unittest.TestCase):
    def test_landmarks_are_current_master_values(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "leon-front.txt"
            p.write_text("11 612 180\n", encoding="utf-8")
            self.assertEqual((11.0, 612.0, 180.0), read_landmarks(p))

    def test_edge_connected_green_is_transparent_but_internal_green_is_preserved(self):
        im = Image.new("RGBA", (5, 5), (20, 20, 20, 255))
        px = im.load()
        for x in range(5):
            px[x, 0] = (0, 255, 0, 255)
            px[x, 4] = (0, 255, 0, 255)
        for y in range(5):
            px[0, y] = (0, 255, 0, 255)
            px[4, y] = (0, 255, 0, 255)
        px[2, 2] = (0, 255, 0, 255)

        keyed = key_green(im)
        out = keyed.load()
        self.assertEqual(0, out[0, 0][3])
        self.assertEqual(255, out[2, 2][3])

    def test_manifest_pins_source_and_full_body_bounds(self):
        source = Image.new("RGBA", (360, 640), (0, 255, 0, 255))
        keyed = Image.new("RGBA", (360, 640), (0, 0, 0, 0))
        px = keyed.load()
        for y in range(11, 613):
            for x in range(130, 231):
                px[x, y] = (40, 30, 20, 255)
        source_bytes = b"source-bytes"
        manifest = build_manifest(source, keyed, source_bytes, (11.0, 612.0, 180.0))
        self.assertEqual(360, manifest["source_width"])
        self.assertEqual(640, manifest["source_height"])
        self.assertEqual(16, manifest["mesh_cols"])
        self.assertEqual(28, manifest["mesh_rows"])
        self.assertEqual(hashlib.sha256(source_bytes).hexdigest(), manifest["source_sha256"])
        self.assertGreater(manifest["alpha_bounds"]["bottom"], 600)
        self.assertEqual([384, 768], manifest["design_size"])


if __name__ == "__main__":
    unittest.main()
